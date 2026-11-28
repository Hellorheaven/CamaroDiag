namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Original, hardware-disconnected E39/E39A GMLAN programming session
/// orchestrator. Unlike ISO 14229 alone, GM programming uses the A5
/// programming-mode handshake and a 16-bit family-specific seed/key.
///
/// Protocol semantics were cross-checked with public GM GMLAN research,
/// including the observed behavior documented by PCM Hammer. No external
/// runtime, binaries, key tables, kernel or source files are imported.
///
/// This class is deliberately NOT exposed via BridgeServer or J2534Device.
/// No ECU flash or RAM-loader upload is performed here.
/// </summary>
internal static class NativeE39ProgrammingSession
{
    internal sealed record Qualification(
        string ExpectedVin,
        uint ExpectedOsId,
        bool HardwareRevisionBenchValidated,
        bool PowerSupplyMonitored,
        bool RecoveryProcedureProven,
        uint MinimumSupplyMillivolts,
        uint MaximumSupplyMillivolts);

    internal interface ITransport
    {
        Task<byte[]> ExchangeAsync(byte[] request, CancellationToken cancellationToken);
        Task SendAsync(byte[] request, CancellationToken cancellationToken);
        Task DelayAsync(TimeSpan duration, CancellationToken cancellationToken);
        Task<uint?> ReadSupplyMillivoltsAsync(CancellationToken cancellationToken);
    }

    internal sealed record Result(
        string Vin, uint OsId, bool InProgrammingMode, bool SecurityGranted);

    internal static async Task<Result> PrepareAsync(
        ITransport transport,
        NativeGmlanIdentification.Identity identity,
        Qualification qualification,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(transport);
        ArgumentNullException.ThrowIfNull(identity);
        ArgumentNullException.ThrowIfNull(qualification);

        // Fail before any programming-mode I/O unless the ECU identity,
        // hardware revision and physical recovery have been attested.
        if (!identity.PhysicalRead ||
            !string.Equals(identity.Vin, qualification.ExpectedVin, StringComparison.Ordinal) ||
            identity.OperatingSystemId != qualification.ExpectedOsId ||
            !qualification.HardwareRevisionBenchValidated ||
            !qualification.PowerSupplyMonitored ||
            !qualification.RecoveryProcedureProven)
            throw new UnauthorizedAccessException(
                "E39 programming session requires matching live VIN/OSID, bench revision, monitored power and proven recovery.");

        if (qualification.MinimumSupplyMillivolts == 0 ||
            qualification.MaximumSupplyMillivolts <= qualification.MinimumSupplyMillivolts)
            throw new ArgumentException("No qualified programming-session voltage window.");
        await RequireSupplyAsync();
        var enter = await transport.ExchangeAsync([0x10, 0x02], cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();
        RequireServiceAck(enter, 0x10, 0x50, 0x02);

        // Communication control is optional for some stock GM firmware.
        // Failure may be ignored only for the documented unsupported NRCs;
        // security denial, bus failure or ambiguous replies are fatal.
        await RequireSupplyAsync();
        var comm = await transport.ExchangeAsync([0x28], cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();
        if (!((comm.Length >= 1 && comm[0] is 0x68 or 0x60) ||
              (comm.Length == 3 && comm[0] == 0x7F &&
               comm[1] == 0x28 && comm[2] is 0x11 or 0x12 or 0x31)))
            throw new InvalidDataException("GM communication-control state is ambiguous.");

        await transport.SendAsync([0x3E], cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();

        // Access is per programming session: unlock only AFTER 10/02.
        await RequireSupplyAsync();
        var response = await transport.ExchangeAsync(
            NativeE39Security.SeedRequest(), cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();
        var seed = NativeE39Security.ParseSeedResponse(response);
        if (!NativeE39Security.IsAlreadyGranted(seed))
        {
            await RequireSupplyAsync();
            response = await transport.ExchangeAsync(
                NativeE39Security.BuildKeyRequest(seed), cancellationToken);
            cancellationToken.ThrowIfCancellationRequested();
            NativeE39Security.RequireUnlockAck(response);
        }

        await RequireSupplyAsync();
        response = await transport.ExchangeAsync([0xA5, 0x01], cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();
        RequireServiceAck(response, 0xA5, 0xE5, null);
        await transport.SendAsync([0x3E], cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();
        await transport.DelayAsync(TimeSpan.FromSeconds(2), cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();

        // GM A5/03 is intentionally fire-and-forget; waiting for a standard
        // positive reply would generate false failures or a duplicate send.
        await RequireSupplyAsync();
        await transport.SendAsync([0xA5, 0x03], cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();
        await transport.DelayAsync(TimeSpan.FromMilliseconds(300), cancellationToken);
        cancellationToken.ThrowIfCancellationRequested();

        await RequireSupplyAsync();
        return new(identity.Vin, identity.OperatingSystemId,
            InProgrammingMode: true, SecurityGranted: true);

        async Task RequireSupplyAsync()
        {
            cancellationToken.ThrowIfCancellationRequested();
            var value = await transport.ReadSupplyMillivoltsAsync(cancellationToken);
            cancellationToken.ThrowIfCancellationRequested();
            if (!value.HasValue || value.Value < qualification.MinimumSupplyMillivolts ||
                value.Value > qualification.MaximumSupplyMillivolts)
                throw new IOException("Supply outside the qualified E39 programming-session window.");
        }
    }

    private static void RequireServiceAck(
        byte[] response, byte service, byte positive, byte? subfunction)
    {
        ArgumentNullException.ThrowIfNull(response);
        if (response.Length == 3 && response[0] == 0x7F &&
            response[1] == service)
            throw new IOException($"GM service 0x{service:X2} denied with NRC 0x{response[2]:X2}.");

        if (response.Length == 0 || response[0] != positive ||
            (subfunction.HasValue &&
             (response.Length < 2 || response[1] != subfunction.Value)))
            throw new InvalidDataException(
                $"Unexpected programming-mode reply for service 0x{service:X2}.");
    }
}
