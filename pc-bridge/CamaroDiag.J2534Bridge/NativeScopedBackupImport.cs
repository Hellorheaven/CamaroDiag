using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Strict, read-only import of v2 archives. Integrity verification never
/// proves physical provenance, complete ECU coverage, or permission to write.
/// </summary>
internal static class NativeScopedBackupImport
{
    internal static NativeScopedMemoryReader.VerifiedCapture VerifyZip(
        string source, J2534MemoryReadScope scope)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(source);
        ArgumentNullException.ThrowIfNull(scope);
        if (!Path.GetExtension(source).Equals(".zip", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("An ECU archive import must be a ZIP.");
        if (scope.Length == 0 || scope.Length > NativeScopedMemoryReader.MaximumUiCaptureBytes)
            throw new InvalidDataException("Out-of-bounds backup scope.");
        // Never decompress an unbounded untrusted archive.
        using var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (input.Length <= 0 || input.Length > 2L * NativeScopedMemoryReader.MaximumUiCaptureBytes + 65536)
            throw new InvalidDataException("Backup archive exceeds the import budget.");
        using var zip = new ZipArchive(input, ZipArchiveMode.Read, leaveOpen: false);
        var expected = new HashSet<string>(StringComparer.Ordinal)
        {
            "manifest.properties", "capture/0/first.bin", "capture/0/second.bin"
        };
        if (zip.Entries.Count != expected.Count ||
            zip.Entries.Any(e => !expected.Remove(e.FullName)) || expected.Count != 0)
            throw new InvalidDataException("Unexpected, missing or repeated backup entry.");
        // Symlinks and special ZIP entry types are not valid evidence files.
        // Some archives encode Unix attributes in the upper 16 bits.
        foreach (var entry in zip.Entries)
        {
            int mode = (entry.ExternalAttributes >> 16) & 0xF000;
            if (mode != 0 && mode != 0x8000)
                throw new InvalidDataException("Backup archive contains a non-regular file.");
        }

        var manifest = zip.GetEntry("manifest.properties")!;
        if (manifest.Length is <= 0 or > 2048)
            throw new InvalidDataException("Invalid backup manifest length.");
        byte[] manifestBytes = ReadExactlyBounded(manifest, checked((int)manifest.Length));
        if (manifestBytes.Any(b => b is not (10 or >= 32 and <= 126)))
            throw new InvalidDataException("Non-ASCII backup manifest.");
        string properties = Encoding.ASCII.GetString(manifestBytes);
        // Android's Java Properties.store() prepends two comment lines.
        // Accept these comments, but reject blank entries, duplicates and
        // any noncanonical data line to keep ZIP metadata unambiguous.
        var allLines = properties.Split('\n');
        var lines = allLines.Take(allLines.Length - 1)
            .Select(line => line.TrimEnd('\r'))
            .Where(line => !line.StartsWith('#'))
            .ToArray();
        if (!properties.EndsWith('\n') || allLines[^1] != "" ||
            lines.Length != 12 ||
            lines.Any(line => line.Length == 0 || line.Count(c => c == '=') != 1))
            throw new InvalidDataException("Invalid backup manifest schema.");
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (string line in lines)
        {
            var parts = line.Split('=', 2);
            if (!values.TryAdd(parts[0], parts[1]))
                throw new InvalidDataException("Duplicate backup manifest field.");
        }
        var keys = new HashSet<string>(StringComparer.Ordinal)
        {
            "format", "vin", "controller", "created", "count", "required",
            "capture.0.region", "capture.0.start", "capture.0.expected",
            "capture.0.origin", "capture.0.sha256", "capture.0.second"
        };
        // The writer emits twelve fields; reject additions or omissions.
        if (values.Count != keys.Count || values.Keys.Any(k => !keys.Contains(k)))
            throw new InvalidDataException("Unrecognized backup manifest field.");
        if (values["format"] != NativeScopedBackupArchive.Format ||
            values["vin"] != scope.ExpectedVin || values["controller"].Length > 128 ||
            values["count"] != "1" || values["required"] != "" ||
            values["capture.0.region"] != "OTHER" ||
            values["capture.0.start"] != scope.StartAddress.ToString(System.Globalization.CultureInfo.InvariantCulture) ||
            values["capture.0.expected"] != scope.Length.ToString(System.Globalization.CultureInfo.InvariantCulture) ||
            values["capture.0.origin"] != "REPORTED_HARDWARE_READ" ||
            values["capture.0.second"] != "true" ||
            !long.TryParse(values["created"], out long created) || created <= 0)
            throw new InvalidDataException("Backup scope, provenance or metadata differs.");
        var digest = values["capture.0.sha256"];
        // Android writes lowercase SHA-256; the Windows exporter uses uppercase.
        if (digest.Length != 64 || digest.Any(c =>
            !(c is >= '0' and <= '9' or >= 'A' and <= 'F' or >= 'a' and <= 'f')))
            throw new InvalidDataException("Invalid backup SHA-256 manifest.");

        var first = ReadExactlyBounded(zip.GetEntry("capture/0/first.bin")!, checked((int)scope.Length));
        var second = ReadExactlyBounded(zip.GetEntry("capture/0/second.bin")!, checked((int)scope.Length));
        if (!CryptographicOperations.FixedTimeEquals(first, second) ||
            !string.Equals(Convert.ToHexString(SHA256.HashData(first)), digest, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Backup acquisitions or SHA-256 do not match.");
        return new NativeScopedMemoryReader.VerifiedCapture(first, scope.StartAddress,
            scope.ExpectedVin, digest,
            checked((int)((scope.Length + (uint)scope.MaxReadBytes - 1) / (uint)scope.MaxReadBytes)), second);
    }

    private static byte[] ReadExactlyBounded(ZipArchiveEntry entry, int expected)
    {
        if (entry.Length != expected)
            throw new InvalidDataException("Backup entry length differs from its manifest.");
        using var stream = entry.Open();
        byte[] data = new byte[expected];
        stream.ReadExactly(data);
        if (stream.ReadByte() != -1)
            throw new InvalidDataException("Unexpected trailing backup bytes.");
        return data;
    }
}
