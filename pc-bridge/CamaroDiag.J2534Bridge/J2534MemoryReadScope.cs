using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Explicit Windows-operator allowlist for REAL, non-destructive UDS memory reads.
///
/// This is never shipped with a guessed GM address map. A documented map must be
/// supplied on the PC via --memory-read-profile and the connected ECU must return
/// the exact expected VIN at DID F190 before even a single 0x23 read is allowed.
/// The profile NEVER authorizes erase, write, SecurityAccess, or download.
/// </summary>
internal sealed class J2534MemoryReadScope
{
    public string ExpectedVin { get; }
    public int RequestCanId { get; }
    public int ResponseCanId { get; }
    public int ProtocolNumber { get; }
    public uint StartAddress { get; }
    public uint Length { get; }
    public int MaxReadBytes { get; }
    public int Session { get; }

    internal J2534MemoryReadScope(
        string expectedVin, int requestCanId, int responseCanId, int protocolNumber,
        uint startAddress, uint length, int maxReadBytes, int session)
    {
        if (!Regex.IsMatch(expectedVin, "^[A-HJ-NPR-Z0-9]{17}$"))
            throw new ArgumentException("Memory read profile requires a valid 17-character VIN.");
        if (protocolNumber is not (33 or 34 or 35 or 36 or 63 or 64))
            throw new ArgumentException("Unsupported J2534 protocol in memory read profile.");
        var can29 = protocolNumber is 34 or 36 or 64;
        var maxId = can29 ? 0x1FFFFFFF : 0x7FF;
        if (requestCanId < 0 || requestCanId > maxId || responseCanId < 0 ||
            responseCanId > maxId || requestCanId == responseCanId)
            throw new ArgumentException("Invalid CAN request/response IDs in memory read profile.");
        if (length == 0 || (ulong)startAddress + length > 0x100000000UL)
            throw new ArgumentException("Invalid or overflowing allowed memory region.");
        if (maxReadBytes is < 1 or > 256)
            throw new ArgumentException("Allowed memory read block length must be 1..256 bytes.");
        if (session is not (0x01 or 0x03))
            throw new ArgumentException("Only default or extended diagnostic read session is allowed.");
        ExpectedVin = expectedVin;
        RequestCanId = requestCanId;
        ResponseCanId = responseCanId;
        ProtocolNumber = protocolNumber;
        StartAddress = startAddress;
        Length = length;
        MaxReadBytes = maxReadBytes;
        Session = session;
    }

    public bool MatchesChannel(int requestCanId, int responseCanId, int protocolNumber) =>
        RequestCanId == requestCanId && ResponseCanId == responseCanId &&
        ProtocolNumber == protocolNumber;

    public bool AllowsReadMemory(byte[] payload)
    {
        if (payload.Length < 8 || payload[0] != 0x23) return false;
        var format = payload[1];
        var addressWidth = format & 0x0F;
        var sizeWidth = format >> 4;
        if (addressWidth != 4 || sizeWidth is < 1 or > 2 ||
            payload.Length != 2 + addressWidth + sizeWidth) return false;
        ulong address = 0;
        for (var i = 0; i < addressWidth; i++) address = (address << 8) | payload[2 + i];
        ulong size = 0;
        for (var i = 0; i < sizeWidth; i++) size = (size << 8) | payload[2 + addressWidth + i];
        return size is > 0 && size <= (ulong)MaxReadBytes &&
               address >= StartAddress && address + size <= (ulong)StartAddress + Length;
    }

    public bool AllowsSessionControl(byte[] payload) =>
        Session == 0x03 && payload.Length == 2 &&
        payload[0] == 0x10 && payload[1] == 0x03;

    public static J2534MemoryReadScope Load(string path)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        var root = doc.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
            throw new ArgumentException("Memory read profile must be a JSON object.");
        // Deliberately no default region, adapter, session or VIN.
        var vin = root.GetProperty("expectedVin").GetString() ?? "";
        var request = checked((int)ParseHexUnsigned(root.GetProperty("requestCanIdHex").GetString()));
        var response = checked((int)ParseHexUnsigned(root.GetProperty("responseCanIdHex").GetString()));
        var start = ParseHexUnsigned(root.GetProperty("startAddressHex").GetString());
        var required = new HashSet<string>(StringComparer.Ordinal)
        {
            "expectedVin", "requestCanIdHex", "responseCanIdHex", "protocolNumber",
            "startAddressHex", "lengthBytes", "maxReadBytes", "session"
        };
        foreach (var property in root.EnumerateObject())
            if (!required.Remove(property.Name))
                throw new ArgumentException("Unexpected or duplicate profile field: " + property.Name);
        if (required.Count > 0)
            throw new ArgumentException("Incomplete memory read profile: " + string.Join(",", required));
        return new J2534MemoryReadScope(
            vin, request, response, root.GetProperty("protocolNumber").GetInt32(),
            start, checked((uint)root.GetProperty("lengthBytes").GetInt64()),
            root.GetProperty("maxReadBytes").GetInt32(), root.GetProperty("session").GetInt32()
        );
    }

    private static uint ParseHexUnsigned(string? value)
    {
        var raw = value?.Trim();
        if (raw?.StartsWith("0x", StringComparison.OrdinalIgnoreCase) == true) raw = raw[2..];
        if (string.IsNullOrEmpty(raw) || !uint.TryParse(raw, NumberStyles.AllowHexSpecifier,
            CultureInfo.InvariantCulture, out var parsed))
            throw new ArgumentException("Invalid unsigned hexadecimal value in memory read profile.");
        return parsed;
    }
}
