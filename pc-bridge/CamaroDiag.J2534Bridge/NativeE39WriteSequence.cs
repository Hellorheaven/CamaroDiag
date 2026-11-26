using System.Runtime.CompilerServices;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Native, fail-closed E39/E39A flash-sector transaction engine.
///
/// This is an injectable component, tested without hardware. It is not exposed
/// through BridgeServer or the Android API; the local CLI requires a trusted qualification. A complete, independently
/// validated E39 flash kernel, ECU identity and bench recovery procedure
/// must be provided before a real hardware adapter can invoke this engine.
/// 
/// Every changed sector is compared with the expected original using ECU
/// CRC before erase, preflight-tested without writing, then erased, written
/// and compared with fresh byte readback as well as ECU CRC before and after write.
/// </summary>
internal static class NativeE39WriteSequence
{
    // A live kernel link has a single mutable ECU session. Do not permit a
    // second programming sequence to interleave its preflight/erase/write
    // traffic with the active owner. Different independent links remain free.
    private static readonly ConditionalWeakTable<IKernelLink, SemaphoreSlim> LinkGates = new();

    private sealed class LinkLease(SemaphoreSlim gate) : IDisposable
    {
        private SemaphoreSlim? ownedGate = gate;

        public void Dispose() => Interlocked.Exchange(ref ownedGate, null)?.Release();
    }

    private static IDisposable AcquireLink(IKernelLink link)
    {
        var gate = LinkGates.GetValue(link, static _ => new SemaphoreSlim(1, 1));
        if (!gate.Wait(0))
            throw new InvalidOperationException(
                "Another E39 programming transaction is already using this ECU link.");
        return new LinkLease(gate);
    }

    internal enum Phase
    {
        Prepared, OriginalCrcVerified, OriginalBytesVerified, TestWriteAccepted, EraseStarted,
        EraseAcknowledged, WriteStarted, WrittenCrcVerified, WrittenBytesVerified,
        FinalBytesVerified, Completed, RecoveryRequired
    }

    internal sealed record Event(Phase Phase, uint Address, string Detail);

    internal interface IDurableJournal
    {
        /// <summary>
        /// Completion implies that the record has been persisted to stable
        /// storage. Implementation must flush before returning to the caller.
        /// </summary>
        Task AppendAsync(Event evidence, CancellationToken cancellationToken);
        // Physically re-acquired ORIGINAL bytes must be durably preserved
        // before ANY erase command. A filename/hash alone is insufficient.
        Task PreserveOriginalBlockAsync(
            uint address, byte[] liveBytes, string expectedSha256,
            CancellationToken cancellationToken);
        // Re-read every preserved sector from the durable store, including
        // its envelope, immediately before an erase. No implicit trust in
        // the original write receipt or an in-memory copy.
        Task RequireOriginalBlocksIntactAsync(CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Journal does not support durable backup re-verification.");
    }

    internal interface IKernelLink
    {
        Task<byte[]> ExchangeAsync(byte[] request, CancellationToken cancellationToken);
        Task<uint?> ReadSupplyMillivoltsAsync(CancellationToken cancellationToken);
        // A qualified hardware implementation must acquire actual flash bytes
        // from the ECU, never return cached data, the requested image or CRC.
        Task<byte[]> ReadFlashBytesAsync(uint address, int length,
            CancellationToken cancellationToken);
    }

    internal sealed record Options(
        int MaximumWriteBytes,
        int ProgramUnitBytes,
        bool BenchProtocolValidated,
        bool RecoveryProcedureTested,
        uint MinimumSupplyMillivolts,
        uint MaximumSupplyMillivolts);

    internal sealed record Result(int SectorsWritten, int BytesWritten);

    private static IReadOnlyList<byte[]> BuildWrites(
        NativeEcuSectorPlanner.Sector sector, Options options, bool dryRun)
    {
        if (options.MaximumWriteBytes is < 1 or > NativeE39FlashCommands.MaximumWriteDataBytes ||
            options.ProgramUnitBytes is < 1 or > NativeE39FlashCommands.MaximumWriteDataBytes ||
            options.MaximumWriteBytes % options.ProgramUnitBytes != 0 ||
            sector.Updated.Length % options.ProgramUnitBytes != 0)
            throw new ArgumentException("Unverified or incompatible flash write block alignment.");
        var frames = new List<byte[]>();
        for (var offset = 0; offset < sector.Length; offset += options.MaximumWriteBytes)
        {
            var length = Math.Min(options.MaximumWriteBytes, sector.Length - offset);
            if (length % options.ProgramUnitBytes != 0)
                throw new ArgumentException("Final flash block violates program-unit alignment.");
            var slice = sector.Updated.AsSpan(offset, length).ToArray();
            frames.Add(NativeE39FlashCommands.BuildWriteBlock(
                checked(sector.Address + (uint)offset), slice, dryRun));
        }
        return frames;
    }

    internal static async Task<Result> RunBenchSequenceAsync(
        IKernelLink link,
        IDurableJournal journal,
        NativeEcuSectorPlanner.Plan plan,
        Options options,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(link);
        ArgumentNullException.ThrowIfNull(journal);
        ArgumentNullException.ThrowIfNull(plan);
        ArgumentNullException.ThrowIfNull(options);
        using var exclusiveLink = AcquireLink(link);
        if (!plan.CanAttemptPhysicalWrite ||
            !options.BenchProtocolValidated || !options.RecoveryProcedureTested)
            throw new UnauthorizedAccessException(
                "E39 write cannot run without a verified exact-firmware map, bench protocol and tested recovery.");
        // Limits must come from the exact qualified ECU / bench profile,
        // not assumptions or an arbitrary user-configured default.
        if (options.MinimumSupplyMillivolts == 0 ||
            options.MaximumSupplyMillivolts <= options.MinimumSupplyMillivolts)
            throw new ArgumentException("No qualified ECU supply-voltage window.");
        if (plan.Sectors.Count == 0)
            throw new InvalidOperationException("No changed ECU sectors.");
        // Precalculate all outgoing packets before ANY ECU traffic.
        var sectors = new List<(NativeEcuSectorPlanner.Sector Sector, byte[][] Dry, byte[][] Write)>();
        // Freeze buffers before computing hashes or awaiting any external code.
        // Reject overlapping ranges before journal writes or ECU traffic.
        ulong previousEnd = 0;
        foreach (var source in plan.Sectors.OrderBy(x => x.Address))
        {
            var sector = source with {
                Original = source.Original.ToArray(),
                Updated = source.Updated.ToArray()
            };
            if (sector.Address < previousEnd ||
                (ulong)sector.Address + (uint)sector.Length > NativeE39ImageInspector.ImageBytes)
                throw new ArgumentException("ECU sector ranges overlap or exceed the E39 image.");
            previousEnd = (ulong)sector.Address + (uint)sector.Length;
            if (sector.Length == 0 || sector.Original.Length != sector.Updated.Length)
                throw new ArgumentException("Invalid frozen ECU sector data.");
            if (!string.Equals(
                sector.OriginalSha256,
                Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(sector.Original)),
                StringComparison.Ordinal) ||
                !string.Equals(
                    sector.UpdatedSha256,
                    Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(sector.Updated)),
                    StringComparison.Ordinal))
                throw new InvalidDataException("ECU sector changed after planning.");
            // A real hardware profile provides erase geometry in the sector size.
            var eraseRequest = NativeE39FlashCommands.BuildSectorErase(
                sector.Address, (uint)sector.Length);
            _ = eraseRequest;
            var dry = BuildWrites(sector, options, dryRun: true).ToArray();
            var write = BuildWrites(sector, options, dryRun: false).ToArray();
            // Compare every assembled wire packet with the target sector NOW,
            // before any ECU operation, and check again immediately pre-TX.
            NativeE39WriteBlockVerifier.RequireCompleteBatch(sector, dry, dryRun: true);
            NativeE39WriteBlockVerifier.RequireCompleteBatch(sector, write, dryRun: false);
            sectors.Add((sector, dry, write));
        }

        var destructiveStarted = false;
        NativeEcuSectorPlanner.Sector? currentSector = null;
        try
        {
            // Journal must be durable BEFORE any interaction that can erase.
            foreach (var item in sectors)
                await journal.AppendAsync(
                    new(Phase.Prepared, item.Sector.Address, item.Sector.OriginalSha256),
                    cancellationToken);

            await RequireSupplyAsync();

            // COMPLETE preflight of the transaction, across ALL sectors,
            // must succeed before the very first erase command.
            foreach (var item in sectors)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var s = item.Sector;
                var query = NativeE39FlashCommands.BuildFlashCrcQuery(
                    s.Address, (uint)s.Length);
                var observed = NativeE39FlashCommands.ParseFlashCrcReply(
                    await link.ExchangeAsync(query, cancellationToken));
                var expected = NativeE39FlashCommands.ComputeCrc32(s.Original);
                if (observed != expected)
                    throw new InvalidDataException(
                        $"Original ECU CRC mismatch at 0x{s.Address:X6}; refusing erase.");
                await journal.AppendAsync(
                    new(Phase.OriginalCrcVerified, s.Address, expected.ToString("X8")),
                    cancellationToken);

                var physicalOriginal = await RequireFreshBytesAsync(s.Address, s.Original,
                    "Original ECU bytes differ from backup; refusing erase.");
                await journal.PreserveOriginalBlockAsync(
                    s.Address, physicalOriginal, s.OriginalSha256, cancellationToken);
                await journal.AppendAsync(
                    new(Phase.OriginalBytesVerified, s.Address, s.OriginalSha256),
                    cancellationToken);

                var dryOffset = 0;
                foreach (var frame in item.Dry)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    dryOffset = checked(dryOffset +
                        NativeE39WriteBlockVerifier.RequireExactFrame(
                            s, frame, dryOffset, dryRun: true));
                    await RequireSupplyAsync();
                    NativeE39FlashCommands.RequireWriteSuccess(
                        await link.ExchangeAsync(frame, cancellationToken));
                }
                if (dryOffset != s.Length)
                    throw new InvalidDataException("Dry-run frames do not match the full intended sector.");
                await journal.AppendAsync(
                    new(Phase.TestWriteAccepted, s.Address, item.Dry.Length.ToString()),
                    cancellationToken);
            }

            var writtenBytes = 0;
            foreach (var item in sectors)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var s = item.Sector;
                currentSector = s;
                // Previous sector operations or a changed connection may have
                // invalidated the all-sector preflight. Reacquire this sector
                // immediately before its erase, never trusting cached bytes.
                await RequireFreshBytesAsync(s.Address, s.Original,
                    "ECU sector changed since preflight; refusing further erase.");
                await RequireSupplyAsync();
                // No network, retransmission or automatic recovery here: the
                // caller's journal must persist the decision before erase.
                await journal.AppendAsync(
                    new(Phase.EraseStarted, s.Address, s.OriginalSha256),
                    cancellationToken);
                // Recheck ALL durable originals, not only the current
                // sector. Previous-sector backups remain essential for
                // recovery after a later sector fails.
                await journal.RequireOriginalBlocksIntactAsync(cancellationToken);
                cancellationToken.ThrowIfCancellationRequested();
                // Recheck after durable journal I/O as it can take time.
                await RequireSupplyAsync();
                destructiveStarted = true;
                NativeE39FlashCommands.RequireEraseSuccess(
                    await link.ExchangeAsync(
                        NativeE39FlashCommands.BuildSectorErase(s.Address, (uint)s.Length),
                        cancellationToken));
                await journal.AppendAsync(
                    new(Phase.EraseAcknowledged, s.Address, s.Length.ToString()),
                    cancellationToken);
                await journal.AppendAsync(
                    new(Phase.WriteStarted, s.Address, s.UpdatedSha256),
                    cancellationToken);
                var nextOffset = 0;
                foreach (var frame in item.Write)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    // Last-mile check of the ACTUAL frame about to be sent.
                    nextOffset = checked(nextOffset +
                        NativeE39WriteBlockVerifier.RequireExactFrame(
                            s, frame, nextOffset, dryRun: false));
                    await RequireSupplyAsync();
                    NativeE39FlashCommands.RequireWriteSuccess(
                        await link.ExchangeAsync(frame, cancellationToken));
                }
                if (nextOffset != s.Length)
                    throw new InvalidDataException("Outgoing write frames did not cover target sector exactly.");

                await RequireSupplyAsync();
                var verify = NativeE39FlashCommands.BuildFlashCrcQuery(
                    s.Address, (uint)s.Length);
                var actual = NativeE39FlashCommands.ParseFlashCrcReply(
                    await link.ExchangeAsync(verify, cancellationToken));
                var expected = NativeE39FlashCommands.ComputeCrc32(s.Updated);
                if (actual != expected)
                    throw new InvalidDataException(
                        $"Written ECU CRC mismatch at 0x{s.Address:X6}; manual recovery required.");
                await journal.AppendAsync(
                    new(Phase.WrittenCrcVerified, s.Address, expected.ToString("X8")),
                    cancellationToken);
                await RequireFreshBytesAsync(s.Address, s.Updated,
                    "ECU post-write byte readback mismatch; manual recovery required.");
                await journal.AppendAsync(
                    new(Phase.WrittenBytesVerified, s.Address, s.UpdatedSha256),
                    cancellationToken);
                writtenBytes += s.Length;
            }

            // A later erase or write can disturb an earlier sector. Re-read
            // every changed sector again after all programming has finished.
            foreach (var item in sectors)
            {
                currentSector = item.Sector;
                await RequireFreshBytesAsync(currentSector.Address, currentSector.Updated,
                    "Final ECU readback differs after programming another sector; recovery required.");
                await journal.AppendAsync(
                    new(Phase.FinalBytesVerified, currentSector.Address, currentSector.UpdatedSha256),
                    cancellationToken);
            }

            await journal.AppendAsync(
                new(Phase.Completed, 0, $"Sectors={sectors.Count};Bytes={writtenBytes}"),
                cancellationToken);
            // A non-cooperative journal can return after cancellation. Do
            // not report success after the operator has stopped the run.
            cancellationToken.ThrowIfCancellationRequested();
            return new Result(sectors.Count, writtenBytes);
        }
        catch (Exception error)
        {
            if (destructiveStarted)
            {
                // Failure journal writes must ignore a previously cancelled
                // operation's token, and must not restart the ECU write.
                try
                {
                    await journal.AppendAsync(
                        new(Phase.RecoveryRequired, currentSector?.Address ?? 0, error.GetType().Name),
                        CancellationToken.None);
                }
                catch (Exception journalError)
                {
                    // The durable EraseStarted marker still prevents declaring
                    // success. Preserve both failures for manual recovery.
                    throw new AggregateException(
                        "ECU state uncertain and recovery journal append failed.",
                        error, journalError);
                }
            }
            throw;
        }

        async Task<byte[]> RequireFreshBytesAsync(uint address, byte[] expected, string error)
        {
            await RequireSupplyAsync();
            var observed = await link.ReadFlashBytesAsync(address, expected.Length,
                cancellationToken);
            cancellationToken.ThrowIfCancellationRequested();
            if (observed is null || observed.Length != expected.Length ||
                !System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(
                    observed, expected))
                throw new InvalidDataException(error);
            return observed;
        }

        async Task RequireSupplyAsync()
        {
            cancellationToken.ThrowIfCancellationRequested();
            var millivolts = await link.ReadSupplyMillivoltsAsync(cancellationToken);
            // Some vendor adapters ignore cancellation and return a value.
            // Cancellation may have arrived DURING this voltage read. No
            // erase/write packet can be issued on that stale observation.
            cancellationToken.ThrowIfCancellationRequested();
            if (!millivolts.HasValue ||
                millivolts.Value < options.MinimumSupplyMillivolts ||
                millivolts.Value > options.MaximumSupplyMillivolts)
                throw new IOException(
                    "ECU supply voltage is unavailable or outside the qualified programming window.");
        }
    }
}
