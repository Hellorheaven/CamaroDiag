using System.Security.Cryptography;
using System.Runtime.CompilerServices;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// One local, dedicated bench transaction from an identified original image
/// through the original RAM loader and native C kernel. No remote bridge route
/// or production firmware qualification is supplied by this component.
/// The board, recovery and kernel must already have been validated independently.
/// </summary>
internal static class NativeE39BenchWorkflow
{
    // An E39 programming session changes diagnostic/security state long
    // before the first flash erase. Reserve the actual transport from
    // identity preflight through RAM loading, full-image verification and
    // final durable journal completion, not just the inner flash link.
    private static readonly ConditionalWeakTable<ITransport, SemaphoreSlim> TransportGates = new();

    private sealed class TransportLease(SemaphoreSlim gate) : IDisposable
    {
        private SemaphoreSlim? owner = gate;
        public void Dispose() => Interlocked.Exchange(ref owner, null)?.Release();
    }

    private static IDisposable ReserveTransport(ITransport transport)
    {
        var gate = TransportGates.GetValue(transport, static _ => new SemaphoreSlim(1, 1));
        if (!gate.Wait(0))
            throw new InvalidOperationException(
                "Another E39 programming workflow already owns this physical transport.");
        return new TransportLease(gate);
    }

    internal interface ITransport : NativeE39ProgrammingSession.ITransport, NativeE39RamLoader.ILink
    {
        Task<NativeGmlanIdentification.Identity> ReadIdentityAsync(CancellationToken cancellationToken);
    }

    internal sealed record Policy(
        NativeE39ProgrammingSession.Qualification Session,
        string ApprovedKernelSha256,
        int MaximumIsoTpPayloadBytes,
        int MaximumReadBytes,
        int MaximumWriteBytes);

    internal sealed record Prepared(
        NativeE39ImagePreflight.Report Image,
        NativeE39RamLoader.Plan Kernel,
        NativeE39WriteSequence.Options Options,
        byte[] Original,
        byte[] Target);

    internal static Prepared Prepare(
        Policy policy, NativeE39BackupQualification.Evidence backup,
        byte[] original, byte[] target, byte[] kernel)
    {
        ArgumentNullException.ThrowIfNull(policy);
        ArgumentNullException.ThrowIfNull(policy.Session);
        ArgumentNullException.ThrowIfNull(backup);
        ArgumentNullException.ThrowIfNull(original);
        ArgumentNullException.ThrowIfNull(target);
        ArgumentNullException.ThrowIfNull(kernel);
        var q = policy.Session;
        if (!q.HardwareRevisionBenchValidated || !q.PowerSupplyMonitored ||
            !q.RecoveryProcedureProven || backup.Vin != q.ExpectedVin ||
            backup.OsId != q.ExpectedOsId || backup.Bytes != NativeE39ImageInspector.ImageBytes)
            throw new UnauthorizedAccessException("Missing matching complete physical backup and independent bench qualification.");
        if (q.MinimumSupplyMillivolts == 0 || q.MaximumSupplyMillivolts <= q.MinimumSupplyMillivolts)
            throw new ArgumentException("Invalid qualified voltage window.");
        if (policy.MaximumIsoTpPayloadBytes is < 17 or > NativeE39RamLoader.MaxJ2534PayloadBytes ||
            policy.MaximumReadBytes < 1 || policy.MaximumReadBytes >= policy.MaximumIsoTpPayloadBytes ||
            policy.MaximumWriteBytes < NativeMpc5566FlashMap.ProgramUnitBytes ||
            policy.MaximumWriteBytes > policy.MaximumIsoTpPayloadBytes - 9 ||
            policy.MaximumWriteBytes % NativeMpc5566FlashMap.ProgramUnitBytes != 0)
            throw new ArgumentException("Invalid qualified native transport payload limits.");
        // Take private copies before any external callback or asynchronous work.
        var before = original.ToArray();
        var after = target.ToArray();
        var ram = kernel.ToArray();
        if (before.Length != backup.Bytes ||
            !string.Equals(backup.Sha256, Hash(before), StringComparison.Ordinal))
            throw new InvalidDataException("Original image is not the independently acquired backup.");
        if (!string.Equals(policy.ApprovedKernelSha256, Hash(ram), StringComparison.Ordinal))
            throw new InvalidDataException("RAM kernel does not match the independently qualified binary.");
        // Only the documented non-uniform MPC5566 sector map is used here.
        // The E39/E39A board-specific qualification above is separate.
        var layout = NativeMpc5566FlashMap.E39PreviewLayout() with { VerifiedForExactFirmware = true };
        var image = NativeE39ImagePreflight.Prepare(before, after, layout,
            q.ExpectedOsId, calibrationOnly: true);
        var staging = NativeE39RamLoader.Build(ram, policy.MaximumIsoTpPayloadBytes);
        var options = new NativeE39WriteSequence.Options(policy.MaximumWriteBytes,
            NativeMpc5566FlashMap.ProgramUnitBytes, true, true,
            q.MinimumSupplyMillivolts, q.MaximumSupplyMillivolts);
        return new Prepared(image, staging, options, before, after);
    }

    internal static async Task<NativeE39WriteSequence.Result> RunAsync(
        ITransport transport, NativeE39WriteSequence.IDurableJournal journal,
        Policy policy, NativeE39BackupQualification.Evidence backup,
        byte[] original, byte[] target, byte[] kernel, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(transport);
        ArgumentNullException.ThrowIfNull(journal);
        // Do not send session/seed-key/kernel commands before reserving this
        // adapter transport. Other distinct transports are unaffected.
        using var exclusiveTransport = ReserveTransport(transport);
        var prepared = Prepare(policy, backup, original, target, kernel);
        var q = policy.Session;
        var session = (NativeE39ProgrammingSession.ITransport)transport;
        token.ThrowIfCancellationRequested();
        var first = await transport.ReadIdentityAsync(token);
        var second = await transport.ReadIdentityAsync(token);
        var identity = NativeGmlanBenchProbe.RequireStableIdentity(first, second);
        await NativeE39ProgrammingSession.PrepareAsync(session, identity, q, token);
        await NativeE39RamLoader.StageAsync(transport, prepared.Kernel,
            q.MinimumSupplyMillivolts, q.MaximumSupplyMillivolts, token);
        var link = new NativeE39KernelLink(session.ExchangeAsync, session.ReadSupplyMillivoltsAsync,
            policy.MaximumReadBytes);
        await link.ProbeAsync(token);

        // Full fresh read of the physical main flash, including unchanged
        // regions, before ANY erase is admitted.
        await RequireImageAsync(link, prepared.Original, prepared.Options, token);

        // The sector engine's Completed event must not reach durable storage
        // until a whole-image reread proves neighbouring/protected bytes too.
        // Any failure during the completion guard propagates inside the
        // sequence's destructive-operation catch and records recovery required.
        var guardedJournal = new CompletionGuard(journal, link, prepared.Target, prepared.Options);
        return await NativeE39WriteSequence.RunBenchSequenceAsync(link, guardedJournal,
            prepared.Image.SectorPlan, prepared.Options, token);
    }

    private static string Hash(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes));

    private static async Task RequireImageAsync(NativeE39KernelLink link, byte[] expected,
        NativeE39WriteSequence.Options options, CancellationToken token)
    {
        const int chunkSize = 32768;
        for (int offset = 0; offset < expected.Length; offset += chunkSize)
        {
            token.ThrowIfCancellationRequested();
            uint? supply = await link.ReadSupplyMillivoltsAsync(token);
            if (!supply.HasValue || supply.Value < options.MinimumSupplyMillivolts ||
                supply.Value > options.MaximumSupplyMillivolts)
                throw new IOException("Unsafe supply during complete image verification.");
            int length = Math.Min(chunkSize, expected.Length - offset);
            byte[] actual = await link.ReadFlashBytesAsync((uint)offset, length, token);
            if (actual.Length != length ||
                !CryptographicOperations.FixedTimeEquals(actual, expected.AsSpan(offset, length)))
                throw new InvalidDataException($"Complete native image differs at chunk 0x{offset:X6}.");
        }
    }

    private sealed class CompletionGuard(
        NativeE39WriteSequence.IDurableJournal journal, NativeE39KernelLink link,
        byte[] expected, NativeE39WriteSequence.Options options) : NativeE39WriteSequence.IDurableJournal
    {
        public Task PreserveOriginalBlockAsync(
            uint address, byte[] liveBytes, string sha256, CancellationToken token) =>
            journal.PreserveOriginalBlockAsync(address, liveBytes, sha256, token);

        public async Task AppendAsync(NativeE39WriteSequence.Event evidence, CancellationToken token)
        {
            if (evidence.Phase == NativeE39WriteSequence.Phase.Completed)
            {
                await RequireImageAsync(link, expected, options, token);
                evidence = evidence with { Detail = evidence.Detail + ";FullImageSHA256=" + Hash(expected) };
            }
            await journal.AppendAsync(evidence, token);
        }
    }
}
