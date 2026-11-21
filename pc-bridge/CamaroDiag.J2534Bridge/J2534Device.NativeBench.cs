using System.Runtime.InteropServices;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class J2534Device
{
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint NativeBenchIoctl(uint channelId, uint ioctlId, IntPtr input, IntPtr output);

    // SAE J2534-1 04.04 section 7.2.14, figure 25. Clear completed replies
    // before a new native command. This is not permission to retry an
    // uncertain request, and late replies still require service correlation.
    private void ClearNativeReceiveQueue()
    {
        if (!NativeLibrary.TryGetExport(_library, "PassThruIoctl", out var entry))
            throw new IOException("Native bench transport requires PassThruIoctl.");
        var ioctl = Marshal.GetDelegateForFunctionPointer<NativeBenchIoctl>(entry);
        Check(ioctl(_channelId, 0x08, IntPtr.Zero, IntPtr.Zero),
            "PassThruIoctl(CLEAR_RX_BUFFER)");
    }

    /// <summary>
    /// LOCAL integration point only, never called by BridgeServer or a UI.
    /// The supplied policy must come from independent real bench qualification;
    /// this repository does not ship such a policy or compatible kernel artifact.
    /// A fresh, dedicated device is held exclusively for the entire workflow.
    /// No retry, power cycle or automatic recovery is attempted.
    /// </summary>
    internal static async Task<NativeE39WriteSequence.Result> RunQualifiedNativeE39BenchAsync(
        J2534Adapter adapter, J2534MemoryReadScope scope,
        NativeE39PhysicalAdmission.QualifiedChannel channel,
        NativeE39BenchWorkflow.Policy policy,
        byte[] original, byte[] target, byte[] kernel,
        NativeE39WriteSequence.IDurableJournal journal, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(adapter);
        ArgumentNullException.ThrowIfNull(scope);
        ArgumentNullException.ThrowIfNull(policy);
        ArgumentNullException.ThrowIfNull(journal);
        NativeE39PhysicalAdmission.RequireMatch(adapter, scope, channel);
        NativeE39BackupQualification.RequireFullScope(scope);
        if (scope.ExpectedVin != policy.Session.ExpectedVin || scope.ProtocolNumber is not (33 or 34))
            throw new UnauthorizedAccessException("Native E39 bench requires the exact VIN and qualified HS-CAN channel.");

        // Freeze inputs and validate proposed images/kernel BEFORE opening a device.
        // This digest is provisional only: actual backup evidence is produced by
        // two physical reads below, and no programming I/O uses it as proof.
        original = original.ToArray(); target = target.ToArray(); kernel = kernel.ToArray();
        var provisional = new NativeE39BackupQualification.Evidence(scope.ExpectedVin,
            policy.Session.ExpectedOsId,
            Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(original)), original.Length);
        _ = NativeE39BenchWorkflow.Prepare(policy, provisional, original, target, kernel);
        token.ThrowIfCancellationRequested();

        using var device = new J2534Device(adapter, scope);
        device.Open(scope.RequestCanId, scope.ResponseCanId,
            scope.ProtocolNumber == 34, scope.ProtocolNumber);
        // Exercise the exact driver ABI before any programming session.
        device.ClearNativeReceiveQueue();
        var measured = device.ReadBatteryMillivolts();
        if (!measured.HasValue || measured.Value < policy.Session.MinimumSupplyMillivolts ||
            measured.Value > policy.Session.MaximumSupplyMillivolts)
            throw new IOException("Bench voltage could not be verified by the selected J2534 adapter.");

        var first = device.ReadGmlanIdentification();
        if (first.Vin != policy.Session.ExpectedVin ||
            first.OperatingSystemId != policy.Session.ExpectedOsId)
            throw new UnauthorizedAccessException("Physical ECU identity differs from qualified policy.");
        var readOnly = new ScopedBackupLink(device);
        bool extended = false;
        NativeScopedMemoryReader.VerifiedCapture capture;
        try
        {
            if (scope.Session == 3)
            {
                var ack = await readOnly.ExchangeAsync([0x10,0x03], token);
                if (ack.Length < 2 || ack[0] != 0x50 || ack[1] != 0x03)
                    throw new IOException("Extended diagnostic read session refused.");
                extended = true;
            }
            capture = await NativeScopedMemoryReader.ReadTwiceAsync(readOnly, scope, null, token);
        }
        finally
        {
            if (extended)
            {
                await NativeScopedMemoryReader.RequireLiveVinAsync(readOnly, scope, CancellationToken.None);
                var ack = await readOnly.ExchangeAsync([0x10,0x01], CancellationToken.None);
                if (ack.Length < 2 || ack[0] != 0x50 || ack[1] != 0x01)
                    throw new IOException("Return to default diagnostic session not confirmed.");
            }
        }
        var second = device.ReadGmlanIdentification();
        var backup = NativeE39BackupQualification.Compare(scope, original, capture, first, second);
        token.ThrowIfCancellationRequested();

        var transport = new NativeBenchTransport(device);
        return await NativeE39BenchWorkflow.RunAsync(transport, journal, policy,
            backup, original, target, kernel, token);
    }

    private sealed class ScopedBackupLink(J2534Device device) : NativeScopedMemoryReader.ITransport
    {
        public Task<byte[]> ExchangeAsync(byte[] request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            var reply = device.Exchange(request, 6000);
            token.ThrowIfCancellationRequested();
            return Task.FromResult(reply);
        }
    }

    // Private: no general-purpose flash-capable J2534 exchange API escapes
    // the dedicated local workflow. After a transport failure this instance
    // refuses all further requests; it cannot be reset or reused.
    private sealed class NativeBenchTransport(J2534Device device)
        : NativeE39BenchWorkflow.ITransport
    {
        private enum Stage { Stock, DownloadReady, Downloading, KernelRunning }
        private bool failed;
        private Stage stage = Stage.Stock;

        public Task<NativeGmlanIdentification.Identity> ReadIdentityAsync(CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (failed || stage != Stage.Stock) throw new InvalidOperationException("No fresh stock identity session.");
            device.ClearNativeReceiveQueue();
            var identity = device.ReadGmlanIdentification();
            token.ThrowIfCancellationRequested();
            return Task.FromResult(identity);
        }

        public Task<uint?> ReadSupplyMillivoltsAsync(CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (failed) throw new IOException("Native bench session failed.");
            return Task.FromResult(device.ReadBatteryMillivolts());
        }

        public Task DelayAsync(TimeSpan duration, CancellationToken token) =>
            Task.Delay(duration, token);

        public Task SendAsync(byte[] request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (failed || stage != Stage.Stock ||
                !(request.AsSpan().SequenceEqual(new byte[]{0x3E}) ||
                  request.AsSpan().SequenceEqual(new byte[]{0xA5,0x03})))
                throw new UnauthorizedAccessException("Native one-way request outside isolated startup sequence.");
            try
            {
                device.SendCore(request);
                token.ThrowIfCancellationRequested();
                if (request[0] == 0xA5) stage = Stage.DownloadReady;
                return Task.CompletedTask;
            }
            catch { failed = true; throw; }
        }

        public Task<byte[]> ExchangeAsync(byte[] request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (failed || request.Length is < 1 or > NativeE39RamLoader.MaxJ2534PayloadBytes ||
                !Allowed(request))
                throw new UnauthorizedAccessException("Native request outside qualified workflow phase.");
            try
            {
                var reply = device.ExchangeCore(request, 10000, nativeBenchReply: true);
                token.ThrowIfCancellationRequested();
                if (stage == Stage.DownloadReady && reply[0] == 0x74)
                    stage = Stage.Downloading;
                else if (stage == Stage.Downloading && request[1] == 0x80 && reply[0] == 0x99)
                    stage = Stage.KernelRunning;
                return Task.FromResult(reply);
            }
            catch { failed = true; throw; }
        }

        private bool Allowed(byte[] request) => stage switch
        {
            Stage.Stock => request.AsSpan().SequenceEqual(new byte[]{0x10,0x02}) ||
                request.AsSpan().SequenceEqual(new byte[]{0x28}) ||
                request.AsSpan().SequenceEqual(new byte[]{0x27,0x01}) ||
                request.Length == 4 && request[0] == 0x27 && request[1] == 2 ||
                request.AsSpan().SequenceEqual(new byte[]{0xA5,0x01}),
            Stage.DownloadReady => request.Length == 5 && request[0] == 0x34 && request[1] == 0,
            Stage.Downloading => request[0] == 0x36 &&
                (request.Length > 6 && request[1] == 0 || request.Length > 10 && request[1] == 0x80),
            Stage.KernelRunning => request.Length >= 2 &&
                (request[0] == 0x3D && (request.Length == 2 && request[1] == 0 ||
                    request.Length == 8 && request[1] == 2 || request.Length == 5 && request[1] == 5) ||
                 request[0] == 0x23 && (request.Length == 7 && request[1] == 0x14 ||
                    request.Length == 8 && request[1] == 0x24) ||
                 request[0] == 0x36 && request.Length > 9 && request[1] is 0 or 0x44),
            _ => false
        };
    }
}
