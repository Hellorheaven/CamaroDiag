using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Strict, read-only import of v2 (legacy) and v3 archives. Integrity verification never
/// proves physical provenance, complete ECU coverage, or permission to write.
/// </summary>
internal static class NativeScopedBackupImport
{
    private static readonly HashSet<string> KnownRegions = new(StringComparer.Ordinal)
    {
        "CALIBRATION", "PROGRAM_FLASH", "DATA_FLASH", "EXTERNAL_EEPROM", "OTHER"
    };
    private static readonly HashSet<string> KnownOrigins = new(StringComparer.Ordinal)
    {
        "IMPORTED_FILE", "SIMULATION", "REPORTED_HARDWARE_READ"
    };

    // An imported file is NEVER a validated physical acquisition. Return only
    // immutable inspection metadata, not writable ECU image bytes or a live
    // VerifiedCapture that could accidentally satisfy a programming preflight.
    internal sealed record Inspection(
        string Vin, uint StartAddress, int ByteCount, string Sha256,
        string DeclaredRegion, string DeclaredOrigin, IReadOnlyList<string> RequiredRegions,
        bool ChannelBound, NativeScopedBackupArchive.FirmwareIdentity? Firmware);

    internal static Inspection VerifyZip(
        string source, J2534MemoryReadScope scope, bool allowInternalStage = false)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(source);
        ArgumentNullException.ThrowIfNull(scope);
        // Only the exporting writer can verify its own hidden temporary ZIP
        // before publication. User-supplied .partial files are never valid
        // archives for the ordinary verifier or CLI.
        bool finalZip = Path.GetExtension(source).Equals(".zip", StringComparison.OrdinalIgnoreCase);
        bool privateStage = allowInternalStage &&
            Path.GetFileName(source).StartsWith(".camarodiag-", StringComparison.Ordinal) &&
            Path.GetExtension(source).Equals(".partial", StringComparison.OrdinalIgnoreCase);
        if (!finalZip && !privateStage)
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
            lines.Length is not (12 or 16 or 18) ||
            lines.Any(line => line.Length == 0 || line.IndexOf('=') <= 0))
            throw new InvalidDataException("Invalid backup manifest schema.");
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (string line in lines)
        {
            int separator = line.IndexOf('=');
            string key = line[..separator];
            string rawValue = line[(separator + 1)..];
            // Java Properties escapes punctuation and non-ASCII values.
            // Only the descriptive controller label is permitted to contain
            // escapes: all security-relevant properties remain canonical.
            string value;
            if (key == "controller")
                value = DecodeControllerLabel(rawValue);
            else
            {
                if (rawValue.Contains('=') || rawValue.Contains('\\'))
                    throw new InvalidDataException("Noncanonical backup metadata.");
                value = rawValue;
            }
            if (!values.TryAdd(key, value))
                throw new InvalidDataException("Duplicate backup manifest field.");
        }
        var keys = new HashSet<string>(StringComparer.Ordinal)
        {
            "format", "vin", "controller", "created", "count", "required",
            "capture.0.region", "capture.0.start", "capture.0.expected",
            "capture.0.origin", "capture.0.sha256", "capture.0.second"
        };
        // The current format binds every archive to the actual operator-selected
        // CAN route. Older v2 imports remain readable but cannot claim that link.
        bool channelBound = values.TryGetValue("format", out var format) &&
            format == NativeScopedBackupArchive.Format;
        if (!channelBound && format != NativeScopedBackupArchive.LegacyFormat)
            throw new InvalidDataException("Unsupported backup archive format.");
        if (channelBound)
        {
            keys.UnionWith(new[] {
                "channel.requestCanId", "channel.responseCanId",
                "channel.protocolNumber", "channel.session"
            });
        }
        bool hasFirmware = channelBound &&
            (values.ContainsKey("ecu.osId") || values.ContainsKey("ecu.osDid"));
        if (hasFirmware) keys.UnionWith(new[] { "ecu.osId", "ecu.osDid" });
        if (values.Count != keys.Count || values.Keys.Any(k => !keys.Contains(k)))
            throw new InvalidDataException("Unrecognized or incomplete backup manifest fields.");
        if (channelBound)
        {
            static bool ExactInt(Dictionary<string, string> fields, string key, int expected) =>
                fields.TryGetValue(key, out var actual) &&
                actual == expected.ToString(System.Globalization.CultureInfo.InvariantCulture);
            if (!ExactInt(values, "channel.requestCanId", scope.RequestCanId) ||
                !ExactInt(values, "channel.responseCanId", scope.ResponseCanId) ||
                !ExactInt(values, "channel.protocolNumber", scope.ProtocolNumber) ||
                !ExactInt(values, "channel.session", scope.Session))
                throw new InvalidDataException(
                    "Backup CAN IDs, J2534 protocol or diagnostic session differ from the approved profile.");
        }
        NativeScopedBackupArchive.FirmwareIdentity? firmware = null;
        if (hasFirmware)
        {
            static uint ReadCanonicalUInt(Dictionary<string, string> fields, string key)
            {
                if (!fields.TryGetValue(key, out var text) ||
                    !uint.TryParse(text, System.Globalization.NumberStyles.None,
                        System.Globalization.CultureInfo.InvariantCulture, out uint value) ||
                    value.ToString(System.Globalization.CultureInfo.InvariantCulture) != text)
                    throw new InvalidDataException("Malformed declared GM firmware identity.");
                return value;
            }
            uint osId = ReadCanonicalUInt(values, "ecu.osId");
            uint osDid = ReadCanonicalUInt(values, "ecu.osDid");
            if (osDid > 255)
                throw new InvalidDataException("Invalid firmware DID.");
            firmware = new NativeScopedBackupArchive.FirmwareIdentity(osId, (byte)osDid);
            if (!firmware.IsValid)
                throw new InvalidDataException("Invalid declared GM firmware OSID or DID.");
        }
        // Android can capture other qualified regions and declare missing
        // required regions. These labels are evidence only, never permission
        // to restore or to access an ECU.
        var requiredValue = values["required"];
        var requiredRegions = requiredValue.Length == 0
            ? Array.Empty<string>() : requiredValue.Split(',');
        if (requiredRegions.Any(region => !KnownRegions.Contains(region)) ||
            requiredRegions.Distinct(StringComparer.Ordinal).Count() != requiredRegions.Length)
            throw new InvalidDataException("Invalid required ECU region metadata.");
        if (values["vin"] != scope.ExpectedVin || values["controller"].Length > 128 ||
            values["count"] != "1" ||
            !KnownRegions.Contains(values["capture.0.region"]) ||
            (channelBound && values["capture.0.region"] != scope.Region) ||
            values["capture.0.start"] != scope.StartAddress.ToString(System.Globalization.CultureInfo.InvariantCulture) ||
            values["capture.0.expected"] != scope.Length.ToString(System.Globalization.CultureInfo.InvariantCulture) ||
            !KnownOrigins.Contains(values["capture.0.origin"]) ||
            values["capture.0.second"] != "true" ||
            !long.TryParse(values["created"], System.Globalization.NumberStyles.None,
                System.Globalization.CultureInfo.InvariantCulture, out long created) || created <= 0)
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
        return new Inspection(scope.ExpectedVin, scope.StartAddress, first.Length,
            Convert.ToHexString(SHA256.HashData(first)),
            values["capture.0.region"], values["capture.0.origin"],
            Array.AsReadOnly(requiredRegions), channelBound, firmware);
    }

    private static string DecodeControllerLabel(string encoded)
    {
        var decoded = new StringBuilder(encoded.Length);
        for (int i = 0; i < encoded.Length; ++i)
        {
            char ch = encoded[i];
            if (ch != '\\')
            {
                if (ch == '=')
                    throw new InvalidDataException("Unescaped controller metadata separator.");
                decoded.Append(ch);
                continue;
            }
            if (++i >= encoded.Length)
                throw new InvalidDataException("Incomplete Java Properties escape.");
            char escaped = encoded[i];
            if (escaped == 'u')
            {
                if (i + 4 >= encoded.Length ||
                    !ushort.TryParse(encoded.AsSpan(i + 1, 4),
                        System.Globalization.NumberStyles.HexNumber,
                        System.Globalization.CultureInfo.InvariantCulture, out ushort scalar))
                    throw new InvalidDataException("Invalid Java Properties unicode escape.");
                decoded.Append((char)scalar);
                i += 4;
            }
            else if (escaped is '\\' or '=' or ':' or ' ' or '#' or '!')
                decoded.Append(escaped);
            else
                throw new InvalidDataException("Unsupported Java Properties controller escape.");
            if (decoded.Length > 128)
                throw new InvalidDataException("Controller label too long.");
        }
        return decoded.ToString();
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
