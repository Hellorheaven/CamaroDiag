using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

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

    internal sealed record FileAssessment(string Schema, string Version, string FileName,
        DateTimeOffset InspectedAtUtc, string? SnapshotSha256, Inspection Inspection,
        string? ExpectedTransaction, string? ExpectedManifest, string? Problem)
    {
        public bool Authenticated => false;
        public bool PhysicalVehicleVerified => false;
        public bool PermitsProgramming => false;
        public bool PermitsAutomaticRecovery => false;
    }

    internal sealed record Options(string Input, string? Output, string? Transaction, string? Manifest);

    private static string? ExpectedHex(string? value,int length)
    {
        if(value is null)return null;
        if(value.Length!=length || !value.All(Uri.IsHexDigit))
            throw new ArgumentException("Expected a complete hexadecimal identity.");
        return value.ToLowerInvariant();
    }

    private static byte[] ReadSnapshot(string path)
    {
        if((File.GetAttributes(path)&(FileAttributes.Directory|FileAttributes.ReparsePoint))!=0)
            throw new InvalidDataException("Expected an unlinked checkpoint file.");
        using var file=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read);
        if(file.Length!=RecordSize)throw new InvalidDataException("Expected exactly 64 checkpoint bytes.");
        var bytes=new byte[RecordSize];
        file.ReadExactly(bytes);
        if(file.ReadByte()!=-1||file.Length!=RecordSize||
            (File.GetAttributes(path)&(FileAttributes.Directory|FileAttributes.ReparsePoint))!=0)
            throw new InvalidDataException("Checkpoint changed during inspection.");
        return bytes;
    }

    internal static FileAssessment AssessFile(string path,string? expectedTransaction=null,string? expectedManifest=null)
    {
        expectedTransaction=ExpectedHex(expectedTransaction,32);
        expectedManifest=ExpectedHex(expectedManifest,64);
        var schema="camarodiag-offline-connector-checkpoint-v1";
        var version=typeof(ConnectorCheckpoint).Assembly.GetName().Version?.ToString()??"";
        var name=Path.GetFileName(path);
        var utc=DateTimeOffset.UtcNow;
        try {
            var bytes=ReadSnapshot(path);
            var inspection=Inspect(bytes,expectedTransaction,expectedManifest);
            if(!CryptographicOperations.FixedTimeEquals(bytes,ReadSnapshot(path)))
                throw new InvalidDataException("Checkpoint changed between reads.");
            return new(schema,version,name,utc,Convert.ToHexString(SHA256.HashData(bytes)),
                inspection,expectedTransaction,expectedManifest,null);
        }catch(Exception error) when(error is IOException or InvalidDataException or UnauthorizedAccessException or
            ArgumentException or NotSupportedException){
            return new(schema,version,name,utc,null,Inspect(ReadOnlySpan<byte>.Empty),
                expectedTransaction,expectedManifest,error.Message);
        }
    }

    internal static Inspection InspectFile(string path)=>AssessFile(path).Inspection;

    internal static string Encode(FileAssessment assessment)=>JsonSerializer.Serialize(assessment,
        new JsonSerializerOptions{WriteIndented=true,PropertyNamingPolicy=JsonNamingPolicy.CamelCase,
            Converters={new JsonStringEnumConverter()}});

    internal static FileAssessment ExportCurrent(string output,string path,FileAssessment displayed)
    {
        var current=AssessFile(path,displayed.ExpectedTransaction,displayed.ExpectedManifest);
        if(displayed.SnapshotSha256 is null || displayed.SnapshotSha256!=current.SnapshotSha256 ||
            Encode(displayed with{InspectedAtUtc=current.InspectedAtUtc})!=Encode(current))
            throw new InvalidDataException("Checkpoint evidence changed or is unavailable.");
        using var stream=new FileStream(output,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        using var writer=new StreamWriter(stream);
        writer.WriteLine(Encode(current));
        writer.Flush();
        stream.Flush(true);
        return current;
    }

    internal static Options Parse(string[] args)
    {
        if(args.Length<2||!args[0].Equals(Command,StringComparison.OrdinalIgnoreCase)||
            string.IsNullOrWhiteSpace(args[1])||args[1].StartsWith("--",StringComparison.Ordinal))
            throw new ArgumentException("Expected checkpoint input.");
        var values=new Dictionary<string,string>(StringComparer.Ordinal);
        for(int i=2;i<args.Length;i++){
            var key=args[i];
            if(key is not ("--output" or "--transaction" or "--manifest") ||
                i+1>=args.Length||string.IsNullOrWhiteSpace(args[i+1])||
                args[i+1].StartsWith("--",StringComparison.Ordinal)||!values.TryAdd(key,args[++i]))
                throw new ArgumentException("Invalid or repeated checkpoint option.");
        }
        return new(args[1],values.GetValueOrDefault("--output"),
            ExpectedHex(values.GetValueOrDefault("--transaction"),32),
            ExpectedHex(values.GetValueOrDefault("--manifest"),64));
    }

    internal static int Run(string[] args)
    {
        try
        {
            var options=Parse(args);
            var assessment=AssessFile(options.Input,options.Transaction,options.Manifest);
            if(options.Output is not null)
                assessment=ExportCurrent(options.Output,options.Input,assessment);
            var report=assessment.Inspection;
            Console.WriteLine("CDJ/1: " + report.Status);
            if (report.Record is { } record) Console.WriteLine($"Stage: {record.Stage}; sequence: {record.Sequence}");
            Console.WriteLine("Offline, unauthenticated evidence. No programming or automatic recovery authorized.");
            return report.Status == Disposition.SafeButBlocked ? 0 : 8;
        }
        catch (Exception error) when (error is IOException or InvalidDataException or UnauthorizedAccessException or ArgumentException
            or NotSupportedException)
        {
            Console.Error.WriteLine("Usage: --inspect-connector-checkpoint <checkpoint.bin> [--transaction HEX32] [--manifest HEX64] [--output new.json]");
            Console.Error.WriteLine("Checkpoint inspection failed: " + error.Message);
            return 6;
        }
    }
}
