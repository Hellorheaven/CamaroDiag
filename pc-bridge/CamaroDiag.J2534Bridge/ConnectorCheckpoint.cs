using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

/// <summary>Offline CDJ/1 evidence; no authenticated state, ECU I/O or retry.</summary>
internal static class ConnectorCheckpoint
{
    internal const string Command = "--inspect-connector-checkpoint";
    internal const int RecordSize = 64;
    internal enum Stage : byte
    {
        Prepared=1, EraseIntent=2, ProgramIntent=3, VerifyIntent=4,
        Verified=5, RestartIntent=6, PostCheck=7, Completed=8, Aborted=9, RecoveryRequired=10
    }
    internal enum Disposition { UnavailableOrCorrupt, RecoveryRequired, SafeButBlocked, IdentityMismatch }
    internal sealed record Record(Stage Stage, uint Sequence, string TransactionHex, string ManifestSha256);
    internal sealed record Inspection(Record? Record, Disposition Status)
    {
        public bool Authenticated => false;
        public bool PermitsProgramming => false;
        public bool PermitsAutomaticRecovery => false;
    }

    internal static Inspection Inspect(ReadOnlySpan<byte> wire, string? expectedTransaction = null,
        string? expectedManifest = null)
    {
        static Inspection Corrupt() => new(null, Disposition.UnavailableOrCorrupt);
        if (wire.Length != RecordSize || !wire[..3].SequenceEqual("CDJ"u8) || wire[3] != 1 ||
            wire[5] != 0 || wire[6] != 0 || wire[7] != 0 || wire[4] is < 1 or > 10)
            return Corrupt();
        if (ConnectorBinaryDiscovery.Crc32(wire[..60]) != BinaryPrimitives.ReadUInt32LittleEndian(wire[60..]))
            return Corrupt();
        if (wire[12..28].IndexOfAnyExcept((byte)0) < 0 || wire[28..60].IndexOfAnyExcept((byte)0) < 0)
            return Corrupt();
        var stage = (Stage)wire[4];
        var seq = BinaryPrimitives.ReadUInt32LittleEndian(wire[8..12]);
        bool reachable = stage switch
        {
            Stage.Aborted => seq == 1,
            Stage.RecoveryRequired => seq is >= 2 and <= 7,
            _ => seq == (uint)stage - 1
        };
        if (!reachable) return Corrupt();
        var record = new Record(stage, seq,
            Convert.ToHexString(wire[12..28]).ToLowerInvariant(),
            Convert.ToHexString(wire[28..60]).ToLowerInvariant());
        if ((expectedTransaction is not null && expectedTransaction != record.TransactionHex) ||
            (expectedManifest is not null && expectedManifest != record.ManifestSha256))
            return new(record, Disposition.IdentityMismatch);
        return new(record, stage is Stage.Prepared or Stage.Aborted or Stage.Completed
            ? Disposition.SafeButBlocked : Disposition.RecoveryRequired);
    }

    internal static Inspection InspectFile(string path)
    {
        if (!File.Exists(path)) return Inspect(ReadOnlySpan<byte>.Empty);
        if ((File.GetAttributes(path) & (FileAttributes.Directory | FileAttributes.ReparsePoint)) != 0)
            return Inspect(ReadOnlySpan<byte>.Empty);
        using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (file.Length != RecordSize) return Inspect(ReadOnlySpan<byte>.Empty);
        Span<byte> bytes = stackalloc byte[RecordSize];
        file.ReadExactly(bytes);
        return file.ReadByte() == -1 ? Inspect(bytes) : Inspect(ReadOnlySpan<byte>.Empty);
    }

    internal static int Run(string[] args)
    {
        if (args.Length != 2 || !args[0].Equals(Command, StringComparison.OrdinalIgnoreCase))
        {
            Console.Error.WriteLine("Usage: --inspect-connector-checkpoint <checkpoint.bin>");
            return 6;
        }
        try
        {
            var report = InspectFile(args[1]);
            Console.WriteLine("CDJ/1: " + report.Status);
            if (report.Record is { } record) Console.WriteLine($"Stage: {record.Stage}; sequence: {record.Sequence}");
            Console.WriteLine("Offline, unauthenticated evidence. No programming or automatic recovery authorized.");
            return report.Status == Disposition.SafeButBlocked ? 0 : 8;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException
            or NotSupportedException)
        {
            Console.Error.WriteLine("Checkpoint inspection failed: " + error.Message);
            return 6;
        }
    }
}
