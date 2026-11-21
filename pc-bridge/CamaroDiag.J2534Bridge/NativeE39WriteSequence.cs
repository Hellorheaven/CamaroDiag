namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Native, fail-closed E39/E39A flash-sector transaction engine.
///
/// This is a hardware-free, injectable component. It cannot be reached from
/// Program, BridgeServer or the Android API. A complete, independently
/// validated E39 flash kernel, ECU identity and bench recovery procedure
/// must be provided before a real hardware adapter can invoke this engine.
/// 
/// Every changed sector is compared with the expected original using ECU
/// CRC before erase, preflight-tested without writing, then erased, written
/// and compared with the expected modified bytes using ECU CRC after write.
/// </summary>
internal static class NativeE39WriteSequence
{
    internal enum Phase
    {
        Prepared, OriginalCrcVerified, TestWriteAccepted, EraseStarted,
        EraseAcknowledged, WriteStarted, WrittenCrcVerified,
        Completed, RecoveryRequired
    }

    internal sealed record Event(Phase Phase, uint Address, string Detail);

    internal interface IDurableJournal
    {
        /// <summary>
        /// Completion implies that the record has been persisted to stable
        /// storage. Implementation must flush before returning to the caller.
        /// </summary>
        Task AppendAsync(Event evidence, CancellationToken cancellationToken);
    }

    internal interface IKernelLink
    {
        Task<byte[]> ExchangeAsync(byte[] request, CancellationToken cancellationToken);
    }

    internal sealed record Options(
        int MaximumWriteBytes,
        int ProgramUnitBytes,
        bool BenchProtocolValidated,
        bool RecoveryProcedureTested);

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
        if (!plan.CanAttemptPhysicalWrite ||
            !options.BenchProtocolValidated || !options.RecoveryProcedureTested)
            throw new UnauthorizedAccessException(
                "E39 write cannot run without a verified exact-firmware map, bench protocol and tested recovery.");
        if (plan.Sectors.Count == 0)
            throw new InvalidOperationException("No changed ECU sectors.");
        // Precalculate all outgoing packets before ANY ECU traffic.
        var sectors = new List<(NativeEcuSectorPlanner.Sector Sector, byte[][] Dry, byte[][] Write)>();
        foreach (var sector in plan.Sectors)
        {
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

                foreach (var frame in item.Dry)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    NativeE39FlashCommands.RequireWriteSuccess(
                        await link.ExchangeAsync(frame, cancellationToken));
                }
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
                // No network, retransmission or automatic recovery here: the
                // caller's journal must persist the decision before erase.
                await journal.AppendAsync(
                    new(Phase.EraseStarted, s.Address, s.OriginalSha256),
                    cancellationToken);
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
                foreach (var frame in item.Write)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    NativeE39FlashCommands.RequireWriteSuccess(
                        await link.ExchangeAsync(frame, cancellationToken));
                }

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
                writtenBytes += s.Length;
            }

            await journal.AppendAsync(
                new(Phase.Completed, 0, $"Sectors={sectors.Count};Bytes={writtenBytes}"),
                cancellationToken);
            return new Result(sectors.Count, writtenBytes);
        }
        catch (Exception error) when (error is IOException or InvalidDataException or
            OperationCanceledException or UnauthorizedAccessException or ArgumentException)
        {
            if (destructiveStarted)
            {
                // Failure journal writes must ignore a previously cancelled
                // operation's token, and must not restart the ECU write.
                await journal.AppendAsync(
                    new(Phase.RecoveryRequired, currentSector?.Address ?? 0, error.GetType().Name),
                    CancellationToken.None);
            }
            throw;
        }
    }
}
