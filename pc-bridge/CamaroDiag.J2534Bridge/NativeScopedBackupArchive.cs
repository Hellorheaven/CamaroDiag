using System.Globalization;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Portable read-only ECU archive, compatible with CamaroDiag Android v3.
/// The archive records reported hardware reads, not certified hardware
/// provenance, an OEM checksum or permission to program the controller.
/// Without a verified complete ECU map, coverage remains partial.
/// </summary>
internal static class NativeScopedBackupArchive
{
    internal const string LegacyFormat = "CamaroDiag ECU offline archive v2";
    internal const string Format = "CamaroDiag ECU offline archive v3";

    internal sealed record FirmwareIdentity(uint OperatingSystemId, byte OsDid)
    {
        internal bool IsValid => OperatingSystemId is > 0 and < uint.MaxValue &&
            OsDid is 0xC1 or 0xC9;
    }

    private static (byte[] First, byte[] Second, string Hash) Snapshot(
        J2534MemoryReadScope scope, NativeScopedMemoryReader.VerifiedCapture capture)
    {
        ArgumentNullException.ThrowIfNull(scope);
        ArgumentNullException.ThrowIfNull(capture);
        if (capture.AcquiredScope != scope)
            throw new InvalidDataException("Backup profile differs from the acquired ECU memory scope.");
        if (capture.SecondBytes is null || ReferenceEquals(capture.Bytes, capture.SecondBytes))
            throw new InvalidDataException("Two distinct ECU acquisitions are required.");
        var first = capture.Bytes.ToArray();
        var second = capture.SecondBytes.ToArray();
        if (scope.Length > NativeScopedMemoryReader.MaximumUiCaptureBytes ||
            scope.StartAddress != capture.StartAddress || scope.ExpectedVin != capture.ExpectedVin ||
            first.Length != scope.Length || first.Length == 0 ||
            capture.BlocksPerPass != (scope.Length + (uint)scope.MaxReadBytes - 1) / (uint)scope.MaxReadBytes ||
            !CryptographicOperations.FixedTimeEquals(first, second))
            throw new InvalidDataException("Capture differs from its independently read scope.");
        var hash = Convert.ToHexString(SHA256.HashData(first));
        if (!string.Equals(hash, capture.Sha256, StringComparison.Ordinal))
            throw new InvalidDataException("Capture changed after independent verification.");
        return (first, second, hash);
    }

    /// <summary>
    /// Check the publicly visible ZIP against the exact capture and optional
    /// physical firmware observation, not merely against its own edited
    /// manifest. A self-consistent forged archive is not capture evidence.
    /// </summary>
    internal static void RequirePublishedCaptureMatch(
        string archivePath, J2534MemoryReadScope scope,
        NativeScopedMemoryReader.VerifiedCapture capture, FirmwareIdentity? firmware)
    {
        var readback = NativeScopedBackupImport.VerifyZip(archivePath, scope);
        if (capture.AcquiredScope != scope || !readback.ChannelBound ||
            readback.Vin != capture.ExpectedVin ||
            readback.StartAddress != capture.StartAddress ||
            readback.ByteCount != capture.Bytes.Length ||
            readback.DeclaredRegion != scope.Region ||
            readback.DeclaredOrigin != "REPORTED_HARDWARE_READ" ||
            !string.Equals(readback.Sha256, capture.Sha256, StringComparison.OrdinalIgnoreCase) ||
            readback.Firmware != firmware ||
            readback.RequiredRegions.Count != 0)
            throw new InvalidDataException(
                "Published ZIP no longer matches the independently verified live ECU acquisition.");
    }

    internal static void SaveNew(string destination, J2534MemoryReadScope scope,
        NativeScopedMemoryReader.VerifiedCapture capture, CancellationToken token,
        FirmwareIdentity? firmware = null)
    {
        if (firmware is not null && !firmware.IsValid)
            throw new InvalidDataException("Invalid observed GM firmware identity.");
        ArgumentException.ThrowIfNullOrWhiteSpace(destination);
        token.ThrowIfCancellationRequested();
        var data = Snapshot(scope, capture);
        var full = Path.GetFullPath(destination);
        var extension = Path.GetExtension(full);
        bool zip = extension.Equals(".zip", StringComparison.OrdinalIgnoreCase);
        if (!zip && !extension.Equals(".bin", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Choose an ECU backup with .zip or .bin extension.");
        if (File.Exists(full) || Directory.Exists(full))
            throw new IOException("An existing backup must not be overwritten.");
        var directory = Path.GetDirectoryName(full) ?? throw new ArgumentException("Invalid destination.");
        var staged = Path.Combine(directory, ".camarodiag-" + Guid.NewGuid().ToString("N") + ".partial");
        bool ownsTemporary = false;
        try
        {
            byte[] metadata = Encoding.ASCII.GetBytes(
                "format=" + Format + "\n" +
                "vin=" + scope.ExpectedVin + "\n" +
                "controller=\n" +
                "created=" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture) + "\n" +
                "count=1\n" +
                "channel.requestCanId=" + scope.RequestCanId.ToString(CultureInfo.InvariantCulture) + "\n" +
                "channel.responseCanId=" + scope.ResponseCanId.ToString(CultureInfo.InvariantCulture) + "\n" +
                "channel.protocolNumber=" + scope.ProtocolNumber.ToString(CultureInfo.InvariantCulture) + "\n" +
                "channel.session=" + scope.Session.ToString(CultureInfo.InvariantCulture) + "\n" +
                (firmware is null ? "" :
                    "ecu.osId=" + firmware.OperatingSystemId.ToString(CultureInfo.InvariantCulture) + "\n" +
                    "ecu.osDid=" + firmware.OsDid.ToString(CultureInfo.InvariantCulture) + "\n") +
                // OTHER deliberately does not infer a region from a numeric address;
                // an empty required map conservatively describes partial ECU coverage.
                "required=\n" +
                "capture.0.region=" + scope.Region + "\n" +
                "capture.0.start=" + scope.StartAddress.ToString(CultureInfo.InvariantCulture) + "\n" +
                "capture.0.expected=" + scope.Length.ToString(CultureInfo.InvariantCulture) + "\n" +
                "capture.0.origin=REPORTED_HARDWARE_READ\n" +
                "capture.0.sha256=" + data.Hash + "\n" +
                "capture.0.second=true\n");
            using (var output = new FileStream(staged, FileMode.CreateNew, FileAccess.Write,
                FileShare.None, 65536, FileOptions.WriteThrough))
            {
                ownsTemporary = true;
                if (zip)
                {
                    using var archive = new ZipArchive(output, ZipArchiveMode.Create, leaveOpen: true);
                    WriteEntry(archive, "manifest.properties", metadata, token);
                    WriteEntry(archive, "capture/0/first.bin", data.First, token);
                    WriteEntry(archive, "capture/0/second.bin", data.Second, token);
                }
                else WriteBytes(output, data.First, token);
                output.Flush(flushToDisk: true);
            }
            token.ThrowIfCancellationRequested();
            using (var input = new FileStream(staged, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                if (zip)
                {
                    using var archive = new ZipArchive(input, ZipArchiveMode.Read);
                    if (archive.Entries.Count != 3)
                        throw new InvalidDataException("Unexpected staged archive contents.");
                    RequireEntry(archive, "manifest.properties", metadata, token);
                    RequireEntry(archive, "capture/0/first.bin", data.First, token);
                    RequireEntry(archive, "capture/0/second.bin", data.Second, token);
                }
                else RequireBytes(input, data.First, token);
            }
            token.ThrowIfCancellationRequested();
            // Verify the COMPLETE ZIP schema, both independent acquisitions
            // and VIN-bound scope before exposing a final backup filename.
            // Entry readback above alone would not catch an invalid manifest.
            if (zip)
                NativeScopedBackupImport.VerifyZip(staged, scope, allowInternalStage: true);
            token.ThrowIfCancellationRequested();
            // Same-directory rename is the publication point. No half-written
            // .zip/.bin exists even after a process crash; no replacement allowed.
            File.Move(staged, full, overwrite: false);
            ownsTemporary = false;
        }
        finally
        {
            if (ownsTemporary)
            {
                try { File.Delete(staged); }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
    }

    private static void WriteEntry(ZipArchive zip, string name, byte[] bytes, CancellationToken token)
    {
        using var output = zip.CreateEntry(name, CompressionLevel.Optimal).Open();
        WriteBytes(output, bytes, token);
    }

    private static void WriteBytes(Stream output, byte[] expected, CancellationToken token)
    {
        const int block = 65536;
        for (int offset = 0; offset < expected.Length; offset += block)
        {
            token.ThrowIfCancellationRequested();
            output.Write(expected.AsSpan(offset, Math.Min(block, expected.Length - offset)));
        }
    }

    private static void RequireEntry(ZipArchive zip, string name, byte[] expected, CancellationToken token)
    {
        var entry = zip.GetEntry(name) ?? throw new InvalidDataException("Missing staged archive entry.");
        if (entry.Length != expected.Length)
            throw new InvalidDataException("Staged archive length differs.");
        using var input = entry.Open();
        RequireBytes(input, expected, token);
    }

    private static void RequireBytes(Stream input, byte[] expected, CancellationToken token)
    {
        byte[] buffer = new byte[65536];
        for (int offset = 0; offset < expected.Length;)
        {
            token.ThrowIfCancellationRequested();
            int length = Math.Min(buffer.Length, expected.Length - offset);
            input.ReadExactly(buffer.AsSpan(0, length));
            if (!CryptographicOperations.FixedTimeEquals(buffer.AsSpan(0, length),
                expected.AsSpan(offset, length)))
                throw new InvalidDataException("Backup disk readback differs from ECU capture.");
            offset += length;
        }
        if (input.ReadByte() != -1)
            throw new InvalidDataException("Backup disk readback contains trailing bytes.");
    }
}
