using System.Globalization;
using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

/// <summary>Read-only check of one ISO-TP exchange against an externally
/// timestamped classical CAN or CAN-FD capture. Never connects to any ECU.
/// Input timestamps and provenance are not independently attested.</summary>
internal static class IsoTpClassicCaptureAudit
{
    internal const string Command = "--isotp-audit-trace";
    internal const string CommandFd = "--isotp-fd-audit-trace";

    private static bool ValidDlc(int length, bool canFd) =>
        length is >= 1 and <= 8 || (canFd && length is 12 or 16 or 20 or 24 or 32 or 48 or 64);
    internal enum Status { Pass, Fail, Inconclusive }
    internal sealed record Frame(long TimeUs, bool TesterTx, uint Id, byte[] Data);
    internal sealed record Result(Status Status, string Reason, int Cf, int Cts);

    internal static Result Analyze(IReadOnlyList<Frame> frames, uint tx, uint rx,
        bool can29, bool senderTx, int nBsMs = 1000, int nCrMs = 1000,
        int maxWaitFrames = 4, byte? txAddress = null, byte? rxAddress = null,
        bool canFd = false)
    {
        var idMax = can29 ? 0x1FFFFFFFu : 0x7FFu;
        if (frames is null || frames.Count > 50000 || tx > idMax ||
            rx > idMax || tx == rx || nBsMs is < 1 or > 30000 ||
            nCrMs is < 1 or > 30000 || maxWaitFrames is < 0 or > 255 ||
            txAddress.HasValue != rxAddress.HasValue)
            throw new ArgumentException("Invalid CAN ID, timeout or capture count.");
        var sender = senderTx ? tx : rx;
        var receiver = senderTx ? rx : tx;
        var senderAddress = senderTx ? txAddress : rxAddress;
        var receiverAddress = senderTx ? rxAddress : txAddress;
        var addressWidth = txAddress.HasValue ? 1 : 0;
        var started = false;
        var waitingForFc = false;
        var overflow = false;
        var reservedStMin = false;
        var total = 0;
        var copied = 0;
        var nextSn = 1;
        var blockSize = 0;
        var inBlock = 0;
        var waitFrames = 0;
        var rxDl = 0;
        var cfCount = 0;
        var ctsCount = 0;
        long lastTime = -1, fcWaitSince = 0, cfWaitSince = 0, stMinUs = 0;
        long? lastCf = null;
        Result Fail(string reason) => new(Status.Fail, reason, cfCount, ctsCount);
        Result Unknown(string reason) => new(Status.Inconclusive, reason, cfCount, ctsCount);

        foreach (var frame in frames)
        {
            if (frame is null || frame.TimeUs < lastTime ||
                frame.TimeUs < 0 || frame.Id > idMax || frame.Data is null ||
                !ValidDlc(frame.Data.Length, canFd))
                throw new InvalidDataException("Malformed/unsorted CAN/CAN-FD capture or DLC.");
            lastTime = frame.TimeUs;
            var own = frame.TesterTx == senderTx && frame.Id == sender;
            var peer = frame.TesterTx != senderTx && frame.Id == receiver;
            if (!own && !peer) continue;
            var expectedAddress = own ? senderAddress : receiverAddress;
            // A different one-byte ISO-TP address on the same CAN ID belongs
            // to a separate logical participant; it must not alter our PDU.
            if (expectedAddress.HasValue && frame.Data[0] != expectedAddress.Value)
                continue;
            if (frame.Data.Length <= addressWidth)
                return Fail("ISO-TP PCI is missing.");
            var kind = frame.Data[addressWidth] & 0xF0;
            if (!started)
            {
                if (!own) continue;
                if (kind == 0)
                {
                    var escaped = frame.Data.Length > 8;
                    if (escaped && (!canFd || frame.Data[addressWidth] != 0 ||
                        frame.Data.Length < addressWidth + 3))
                        return Fail("FD SingleFrame with DLC > 8 needs escaped SF_DL.");
                    var header = escaped ? 2 : 1;
                    var n = escaped ? frame.Data[addressWidth + 1] :
                        frame.Data[addressWidth] & 15;
                    return n >= 1 && n <= frame.Data.Length - addressWidth - header
                        ? new(Status.Pass, "SingleFrame syntax only; no STmin evidence.", 0, 0)
                        : Fail("Invalid single-frame size.");
                }
                if (kind != 0x10 || frame.Data.Length < 8)
                    return Fail("Expected SF or valid FirstFrame.");
                rxDl = frame.Data.Length;
                var length12 = ((frame.Data[addressWidth] & 15) << 8) |
                    frame.Data[addressWidth + 1];
                if (length12 == 0)
                {
                    // ISO 15765-2 extended FF_DL is a 32-bit big-endian
                    // length. On classic CAN its FF still carries payload.
                    if (frame.Data.Length < addressWidth + 7)
                        return Fail("Extended FF has no data bytes.");
                    var length32 = BinaryPrimitives.ReadUInt32BigEndian(
                        frame.Data.AsSpan(addressWidth + 2, 4));
                    if (length32 <= 4095 || length32 > IsoTpCanFdSession.MaximumPayloadBytes)
                        return Fail("Invalid extended 32-bit FF length.");
                    total = (int)length32;
                    copied = frame.Data.Length - 6 - addressWidth;
                }
                else
                {
                    total = length12;
                    var singleCapacity = frame.Data.Length > 8 ?
                        frame.Data.Length - 2 - addressWidth : 7 - addressWidth;
                    if (total <= singleCapacity)
                        return Fail("FF length fits within a SingleFrame.");
                    copied = frame.Data.Length - 2 - addressWidth;
                }
                waitingForFc = started = true;
                fcWaitSince = frame.TimeUs;
                continue;
            }
            if (peer)
            {
                if (kind != 0x30) continue;
                if (!waitingForFc || frame.Data.Length < addressWidth + 3)
                    return Fail("Unexpected or malformed FlowControl.");
                if (frame.TimeUs - fcWaitSince > nBsMs * 1000L)
                    return Fail("FlowControl exceeded N_Bs.");
                var status = frame.Data[addressWidth] & 15;
                if (status == 1)
                {
                    if (++waitFrames > maxWaitFrames)
                        return Fail("FC/WAIT exceeded configured WFTmax.");
                    fcWaitSince = frame.TimeUs;
                    continue;
                }
                if (status == 2)
                {
                    overflow = true;
                    waitingForFc = false;
                    continue;
                }
                if (status != 0) return Fail("Invalid FC status.");
                reservedStMin |= IsoTpCanFdSession.IsReservedStMin(frame.Data[addressWidth + 2]);
                var value = frame.Data[addressWidth + 2];
                stMinUs = reservedStMin ? 127000L :
                    value <= 0x7F ? value * 1000L :
                    value is >= 0xF1 and <= 0xF9 ? (value - 0xF0) * 100L :
                    127000L;
                blockSize = frame.Data[addressWidth + 1];
                inBlock = 0;
                waitFrames = 0;
                waitingForFc = false;
                cfWaitSince = frame.TimeUs;
                ctsCount++;
                continue;
            }
            if (overflow) return Fail("Sender continued after FC/OVFLW.");
            if (waitingForFc) return Fail("CF was emitted before FC/CTS.");
            if (kind != 0x20 || frame.Data.Length < addressWidth + 2 ||
                frame.Data.Length > rxDl ||
                (frame.Data[addressWidth] & 15) != nextSn)
                return Fail("Bad CF type/sequence.");
            if (frame.TimeUs - cfWaitSince > nCrMs * 1000L)
                return Fail("CF exceeded the N_Cr deadline.");
            if (lastCf.HasValue && frame.TimeUs - lastCf.Value < stMinUs)
                return Fail("CF-to-CF bus timing violates STmin.");
            var remaining = total - copied;
            var take = Math.Min(remaining, frame.Data.Length - 1 - addressWidth);
            if (take == 0 || (take < remaining && frame.Data.Length != rxDl))
                return Fail("Short non-final CF.");
            copied += take;
            lastCf = frame.TimeUs;
            cfWaitSince = frame.TimeUs;
            cfCount++;
            inBlock++;
            nextSn = (nextSn + 1) & 15;
            if (copied == total)
                return new(Status.Pass,
                    "One complete FF/FC/CF transfer matches capture timing; hardware origin is not attested.",
                    cfCount, ctsCount);
            if (blockSize != 0 && inBlock >= blockSize)
            {
                waitingForFc = true;
                fcWaitSince = frame.TimeUs;
                waitFrames = 0;
            }
        }
        return Unknown(overflow ? "Peer aborted with FC/OVFLW." :
            started ? "Capture ended before complete PDU." :
            "No matching sender PDU.");
    }

    internal static IReadOnlyList<Frame> ReadCapture(string path, bool can29,
        bool canFd = false)
    {
        var file = new FileInfo(path);
        if (!file.Exists || file.Length > 16 * 1024 * 1024)
            throw new InvalidDataException("Missing or oversized bus capture.");
        var output = new List<Frame>();
        var header = false;
        long lastTime = -1;
        foreach (var raw in File.ReadLines(path))
        {
            var row = raw.Trim();
            if (row.Length == 0 || row.StartsWith('#')) continue;
            if (!header)
            {
                if (!row.Equals("timestamp_us,direction,can_id,payload_hex",
                    StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("Required independent bus CSV header missing.");
                header = true;
                continue;
            }
            var cols = row.Split(',', StringSplitOptions.TrimEntries);
            if (cols.Length != 4 || !long.TryParse(cols[0], NumberStyles.None,
                CultureInfo.InvariantCulture, out var timestamp) ||
                timestamp < lastTime)
                throw new InvalidDataException("Invalid or unordered microsecond timestamp.");
            lastTime = timestamp;
            var direction = cols[1].ToUpperInvariant() switch
            {
                "TX" => true, "RX" => false,
                _ => throw new InvalidDataException("Direction must be TX/RX.")
            };
            var hex = cols[2].StartsWith("0x", StringComparison.OrdinalIgnoreCase)
                ? cols[2][2..] : cols[2];
            if (!uint.TryParse(hex, NumberStyles.HexNumber, CultureInfo.InvariantCulture,
                out var id) || id > (can29 ? 0x1FFFFFFFu : 0x7FFu))
                throw new InvalidDataException("Invalid CAN ID.");
            byte[] data;
            try { data = Convert.FromHexString(cols[3]); }
            catch (FormatException error)
            {
                throw new InvalidDataException("Invalid hex CAN payload.", error);
            }
            if (!ValidDlc(data.Length, canFd) || output.Count >= 50000)
                throw new InvalidDataException("Invalid CAN/CAN-FD DLC or capture length.");
            output.Add(new(timestamp, direction, id, data));
        }
        if (!header) throw new InvalidDataException("No capture CSV header.");
        return output;
    }

    internal static int Run(string[] args)
    {
        try
        {
            if (args.Length < 9 || args.Length % 2 != 1 ||
                (!args[0].Equals(Command, StringComparison.OrdinalIgnoreCase) &&
                 !args[0].Equals(CommandFd, StringComparison.OrdinalIgnoreCase)))
                throw new ArgumentException("Usage: --isotp-audit-trace OR --isotp-fd-audit-trace --capture-file <bus.csv> --tx-id <hex> --rx-id <hex> --sender tx|rx [--extended true|false] [--tx-address <hex> --rx-address <hex>] [--n-bs-ms 1000] [--n-cr-ms 1000] [--max-wait-frames 4]");
            var options = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            for (var i = 1; i < args.Length; i += 2)
                if (!new[] {"--capture-file", "--tx-id", "--rx-id",
                    "--sender", "--extended", "--n-bs-ms", "--n-cr-ms",
                    "--max-wait-frames", "--tx-address", "--rx-address" }
                    .Contains(args[i], StringComparer.OrdinalIgnoreCase) ||
                    !options.TryAdd(args[i], args[i + 1]))
                    throw new ArgumentException("Unknown or duplicate option.");
            string Req(string key) => options.TryGetValue(key, out var v) ? v :
                throw new ArgumentException("Missing " + key);
            uint Id(string key)
            {
                var hex = Req(key);
                if (hex.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
                    hex = hex[2..];
                if (!uint.TryParse(hex, NumberStyles.HexNumber,
                    CultureInfo.InvariantCulture, out var id))
                    throw new ArgumentException("Invalid hex CAN ID.");
                return id;
            }
            var canFd = args[0].Equals(CommandFd, StringComparison.OrdinalIgnoreCase);
            var can29 = options.TryGetValue("--extended", out var flag) && bool.Parse(flag);
            var senderTx = Req("--sender").ToLowerInvariant() switch
            {
                "tx" => true, "rx" => false,
                _ => throw new ArgumentException("Sender must be tx or rx.")
            };
            var nBs = options.TryGetValue("--n-bs-ms", out var value)
                ? int.Parse(value, CultureInfo.InvariantCulture) : 1000;
            var nCr = options.TryGetValue("--n-cr-ms", out var nCrText)
                ? int.Parse(nCrText, CultureInfo.InvariantCulture) : 1000;
            var maxWait = options.TryGetValue("--max-wait-frames", out var waitText)
                ? int.Parse(waitText, CultureInfo.InvariantCulture) : 4;
            byte? txAddress = options.ContainsKey("--tx-address")
                ? checked((byte)Id("--tx-address")) : null;
            byte? rxAddress = options.ContainsKey("--rx-address")
                ? checked((byte)Id("--rx-address")) : null;
            var result = Analyze(ReadCapture(Req("--capture-file"), can29, canFd),
                Id("--tx-id"), Id("--rx-id"), can29, senderTx, nBs, nCr,
                maxWait, txAddress, rxAddress, canFd);
            Console.WriteLine($"ISO-TP {(canFd ? "CAN-FD" : "classic CAN")}: {result.Status}, CF={result.Cf}, CTS={result.Cts}");
            Console.WriteLine(result.Reason);
            Console.WriteLine("Input is user supplied; no attestation of bus source, ECU or programming capability.");
            return result.Status switch
            {
                Status.Pass => 0, Status.Fail => 7, _ => 8
            };
        }
        catch (Exception error) when (error is ArgumentException or IOException or
            UnauthorizedAccessException or NotSupportedException or
            OverflowException or FormatException or InvalidOperationException)
        {
            Console.Error.WriteLine("ISO-TP capture audit invalid: " + error.Message);
            return 6;
        }
    }
}
