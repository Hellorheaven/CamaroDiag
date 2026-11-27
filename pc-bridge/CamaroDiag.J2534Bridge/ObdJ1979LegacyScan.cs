using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>Separate ISO 15031-5:2006 legacy emissions read campaign.
/// All records are correlated to one selected response header, not a
/// physically addressed request or a complete vehicle inventory.</summary>
internal static class ObdJ1979LegacyScan
{
    internal sealed record Evidence(string Source, string RawHex, string PayloadHex, uint Timestamp,
        string? InFrameResponseHex);
    internal sealed record Step(string RequestHex, string? TxHex, IReadOnlyList<Evidence> Responses,
        string Status, string? Detail, long DurationMs);
    internal sealed record Result(string? ObservedVin, string SelectedResponseSource,
        bool TransportObserved, bool Completed, string? StopReason, IReadOnlyList<Step> Steps,
        IReadOnlyList<string> NotExecuted, long DurationMs);

    internal static Result Run(byte source, Func<byte[], int, ObdLegacyJ2534Transport.Exchange> exchange,
        Action requireActive, TimeSpan budget)
    {
        ArgumentNullException.ThrowIfNull(exchange);
        ArgumentNullException.ThrowIfNull(requireActive);
        if (source is 0 or 0xF1 or 0xFF || budget <= TimeSpan.Zero || budget > TimeSpan.FromMinutes(10))
            throw new ArgumentException("Invalid legacy campaign scope.");
        var timer = Stopwatch.StartNew();
        var steps = new List<Step>();
        string? stop = null, vin = null;
        bool observed = false;
        Result Finish() => new(vin, source.ToString("X2"), observed, stop is null, stop,
            steps.ToArray(), ["MODE_04_CLEAR", "MODE_08_CONTROL"], timer.ElapsedMilliseconds);

        byte[][] Read(byte[] request, Func<byte[], bool> valid, bool single = false)
        {
            if (stop is not null) return [];
            try { requireActive(); }
            catch (OperationCanceledException) { stop = "CANCELLED"; return []; }
            if (!ObdLegacyJ2534Transport.AllowsRead(request))
                throw new InvalidOperationException("Non-read request in legacy campaign.");
            var remaining = budget - timer.Elapsed;
            if (remaining.TotalMilliseconds < 100)
            {
                stop = "BUDGET_EXHAUSTED";
                return [];
            }
            var watch = Stopwatch.StartNew();
            ObdLegacyJ2534Transport.Exchange? result = null;
            Evidence[] evidence = [];
            string hex = Convert.ToHexString(request);
            try
            {
                result = exchange(request, (int)Math.Min(1800, remaining.TotalMilliseconds));
                requireActive();
                evidence = result.Replies.Select(packet => new Evidence(packet.Source.ToString("X2"),
                    packet.RawHex, Convert.ToHexString(packet.Payload), packet.Timestamp,
                    packet.InFrameResponseHex)).ToArray();
                if(timer.Elapsed >= budget){
                    stop="BUDGET_EXHAUSTED";
                    steps.Add(new(hex,result.TxHex,evidence,"NOT_TESTED","LATE_RESPONSE",watch.ElapsedMilliseconds));
                    return [];
                }
                var packets = result.Replies.Select(p => p.Payload).ToArray();
                if (packets.Length == 0)
                {
                    steps.Add(new(hex, result.TxHex, evidence, "NOT_TESTED", "NO_RESPONSE", watch.ElapsedMilliseconds));
                    return [];
                }
                if (result.Replies.Any(p => p.Source != source)){
                    stop="RESPONSE_SOURCE_CHANGED";
                    throw new InvalidDataException("Response source changed.");
                }
                if (packets.All(p => p.Length == 3 && p[0] == 0x7F && p[1] == request[0] && p[2] is not (0 or 0x78)))
                {
                    steps.Add(new(hex, result.TxHex, evidence, "NOT_TESTED",
                        string.Join(",", packets.Select(p => $"NRC_{p[2]:X2}")), watch.ElapsedMilliseconds));
                    return [];
                }
                if (packets.Any(p => !valid(p)) || single && packets.Length != 1)
                    throw new InvalidDataException("Ambiguous or malformed legacy response.");
                steps.Add(new(hex, result.TxHex, evidence, "PASSED", null, watch.ElapsedMilliseconds));
                return packets;
            }
            catch (OperationCanceledException)
            {
                stop = "CANCELLED";
                steps.Add(new(hex, result?.TxHex, evidence, "NOT_TESTED", stop, watch.ElapsedMilliseconds));
            }
            catch (TimeoutException)
            {
                steps.Add(new(hex, result?.TxHex, evidence, "NOT_TESTED", "TIMEOUT", watch.ElapsedMilliseconds));
            }
            catch (InvalidDataException error)
            {
                steps.Add(new(hex, result?.TxHex, evidence, "FAILED", error.Message, watch.ElapsedMilliseconds));
            }
            catch (IOException error)
            {
                stop = "TRANSPORT_ERROR";
                steps.Add(new(hex, result?.TxHex, evidence, "FAILED", error.GetType().Name, watch.ElapsedMilliseconds));
            }
            return [];
        }

        static bool Page(byte[] r, byte mode, int page, int offset, byte? filler) =>
            r.Length == offset + 4 && r[0] == mode + 0x40 && r[1] == page &&
            (!filler.HasValue || r[2] == filler.Value) &&
            (page != 0xE0 || (r[^1] & 1) == 0);

        SortedSet<byte> Inventory(byte mode, int offset, byte? filler, Func<byte, byte[]> request,
            byte[]? first = null)
        {
            var ids = new SortedSet<byte>();
            for (int page = 0; page <= 0xE0 && stop is null; page += 32)
            {
                var rows = page == 0 && first is not null ? new byte[][] { first } :
                    Read(request((byte)page), r => Page(r, mode, page, offset, filler), single: true);
                if (rows.Length != 1) break;
                var data = rows[0];
                for (int bit = 0; bit < 31; bit++)
                    if ((data[offset + bit / 8] & (0x80 >> (bit % 8))) != 0)
                        ids.Add((byte)(page + bit + 1));
                if ((data[^1] & 1) == 0) break;
            }
            return ids;
        }

        var initial = Read([1, 0], r => Page(r, 1, 0, 2, null), single: true);
        if (initial.Length != 1)
        {
            stop ??= "INITIAL_PID00_UNVERIFIED";
            return Finish();
        }
        observed = true;

        // Acquire identity early; the selected source, not arrival order
        // across multiple ECUs, binds the numbered fragments.
        var info = Inventory(9, 3, 1, page => [9, page]);
        var counts = new Dictionary<byte, byte>();
        foreach (byte type in info)
        {
            if (stop is not null) break;
            if (type % 2 == 1)
            {
                var rows = Read([9, type], r => r.Length == 3 && r[0] == 0x49 &&
                    r[1] == type && r[2] > 0, single: true);
                if (rows.Length == 1) counts[type] = rows[0][2];
                continue;
            }
            var fragments = Read([9, type], r => r.Length == 7 && r[0] == 0x49 &&
                r[1] == type && r[2] > 0);
            if (fragments.Length == 0) continue;
            if (!counts.TryGetValue((byte)(type - 1), out byte count) ||
                fragments.Length != count ||
                !fragments.Select(x => (int)x[2]).SequenceEqual(Enumerable.Range(1, count)))
            {
                steps[^1] = steps[^1] with { Status = "FAILED", Detail = "INCOMPLETE_OR_DUPLICATE_SEQUENCE" };
                continue;
            }
            if (type == 2)
            {
                var bytes = fragments.SelectMany(x => x.Skip(3)).ToArray();
                if (count == 5 && bytes.AsSpan(0, 3).SequenceEqual(new byte[3]))
                {
                    var candidate = Encoding.ASCII.GetString(bytes, 3, 17);
                    if (Regex.IsMatch(candidate, @"\A[A-HJ-NPR-Z0-9]{17}\z"))
                        vin = candidate;
                }
                if (vin is null)
                    steps[^1] = steps[^1] with { Status = "FAILED", Detail = "INVALID_VIN_RECORD" };
                else steps[^1] = steps[^1] with { Detail = vin };
            }
            if (type == 4 && count % 4 != 0)
            {
                steps[^1] = steps[^1] with { Status = "FAILED", Detail = "INVALID_CALID_COUNT" };
                continue;
            }
            if (type == 4)
            {
                var bytes = fragments.SelectMany(x => x.Skip(3)).ToArray();
                var calibrationIds = bytes.Chunk(16).Select(x => Encoding.ASCII.GetString(x).TrimEnd(' ', '\0')).ToArray();
                if (bytes.All(x => x == 0 || x is >= 0x20 and <= 0x7E) &&
                    calibrationIds.All(x => x.Length > 0 && x.All(c => c is >= ' ' and <= '~')))
                    steps[^1] = steps[^1] with { Detail = string.Join(",", calibrationIds) };
                else steps[^1] = steps[^1] with { Status = "FAILED", Detail = "INVALID_CALID_RECORD" };
            }
            if (type == 6)
                steps[^1] = steps[^1] with { Detail = string.Join(",", fragments.Select(x => Convert.ToHexString(x.AsSpan(3)))) };
        }

        foreach (byte mode in new byte[] { 3, 7, 10 })
        {
            var rows = Read([mode], r => r.Length == 7 && r[0] == mode + 0x40);
            if (rows.Length > 0)
            {
                var codes = rows.SelectMany(r => ObdJ1979Dtc.DecodePairs(r.AsSpan(1)))
                    .Select(c => c.Display).Distinct().ToArray();
                steps[^1] = steps[^1] with { Detail = string.Join(",", codes) };
            }
        }

        static bool PidRecord(byte[] r, byte mode, byte pid)
        {
            int offset = mode == 2 ? 3 : 2;
            if (r.Length <= offset || r[0] != mode + 0x40 || r[1] != pid ||
                mode == 2 && r[2] != 0) return false;
            int? width = pid switch
            {
                1 => 4,
                0x13 or 0x1D => 1,
                2 when mode == 2 => 2,
                _ => ObdJ1979PidValues.DataLength(pid)
            };
            return width is null || r.Length == offset + width.Value;
        }

        var sensors = new SortedSet<byte>();
        var pids = Inventory(1, 2, null, page => [1, page], initial[0]);
        foreach (byte pid in pids)
        {
            var rows = Read([1, pid], r => PidRecord(r, 1, pid), single: true);
            if (rows.Length == 0) continue;
            if (pid is 0x13 or 0x1D)
                for (int bit = 0; bit < 8; bit++)
                    if ((rows[0][2] & (1 << bit)) != 0) sensors.Add((byte)(1 << bit));
            var value = ObdJ1979PidValues.Decode([1, pid], rows[0]);
            if (value is not null)
                steps[^1] = steps[^1] with { Detail = FormattableString.Invariant($"{value.Name}={value.Number} {value.Unit}") };
            else steps[^1] = steps[^1] with { Detail = "RAW_RECORD" };
        }
        var trigger = Read([2, 2, 0], r => PidRecord(r, 2, 2), single: true);
        if (trigger.Length == 1 && (trigger[0][3] != 0 || trigger[0][4] != 0))
            foreach (var pid in Inventory(2, 3, 0, page => [2, page, 0]).Where(p => p != 2))
                Read([2, pid, 0], r => PidRecord(r, 2, pid), single: true);
        foreach (byte mask in sensors)
            foreach (byte tid in Inventory(5, 3, mask, page => [5, page, mask]))
                Read([5, tid, mask], r => r.Length == (tid is >= 1 and <= 4 ? 4 : 6) &&
                    r[0] == 0x45 && r[1] == tid && r[2] == mask);
        foreach (byte tid in Inventory(6, 3, 0xFF, page => [6, page]))
            Read([6, tid], r => r.Length == 7 && r[0] == 0x46 && r[1] == tid);
        _ = Inventory(8, 3, 0, page => [8, page, 0, 0, 0, 0, 0]);
        try { requireActive(); }
        catch (OperationCanceledException) { stop ??= "CANCELLED"; }
        return Finish();
    }
}
