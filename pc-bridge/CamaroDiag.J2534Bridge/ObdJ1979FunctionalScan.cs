using System.Diagnostics;

namespace CamaroDiag.J2534Bridge;

/// <summary>Independent, read-only J1979 physical CAN recipe. One selected
/// responder, explicit request/response evidence, no simulated fallback.
/// The exchange delegate is responsible for transport/address correlation.</summary>
internal static class ObdJ1979FunctionalScan
{
    internal sealed record Step(string RequestHex, string? ResponseHex,
        string Status, string? Detail, long DurationMs);
    internal sealed record Result(string? ObservedVin, IReadOnlyList<Step> Steps,
        bool Completed, string[] NotExecuted);

    internal static Result Run(Func<byte[], int, byte[]> exchange,
        Action requireActive, TimeSpan budget)
    {
        ArgumentNullException.ThrowIfNull(exchange);
        ArgumentNullException.ThrowIfNull(requireActive);
        if (budget <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(budget));
        var watch = Stopwatch.StartNew();
        var steps = new List<Step>();
        string? vin = null;
        bool expired = false;

        byte[]? Read(byte[] request, Func<byte[], string>? decode = null)
        {
            requireActive();
            if (!StandardObdPhysicalPolicy.AllowsRead(request))
                throw new InvalidOperationException("Non-read request in physical recipe.");
            var remaining = budget - watch.Elapsed;
            if (remaining.TotalMilliseconds < 100)
            {
                expired = true;
                steps.Add(new(Convert.ToHexString(request), null, "NOT_TESTED",
                    "BUDGET_EXHAUSTED", 0));
                return null;
            }
            var timer = Stopwatch.StartNew();
            byte[]? response = null;
            try
            {
                response = exchange(request, (int)Math.Min(2500, remaining.TotalMilliseconds));
                requireActive();
                var reply = ObdJ1979Transaction.Decode(request, response,
                    expectIdentifierEcho: request.Length >= 2);
                if (reply.Kind == ObdJ1979Transaction.ReplyKind.Negative)
                {
                    steps.Add(new(Convert.ToHexString(request), Convert.ToHexString(response),
                        "NOT_TESTED", $"NRC_{reply.NegativeResponseCode:X2}", timer.ElapsedMilliseconds));
                    return null;
                }
                var detail = decode is null
                    ? ObdJ1979Interpretation.Interpret(request, response).Description
                    : decode(response);
                steps.Add(new(Convert.ToHexString(request), Convert.ToHexString(response),
                    "PASSED", detail, timer.ElapsedMilliseconds));
                return response;
            }
            catch (OperationCanceledException) { throw; }
            catch (TimeoutException)
            {
                requireActive();
                steps.Add(new(Convert.ToHexString(request), null, "NOT_TESTED",
                    "TIMEOUT", timer.ElapsedMilliseconds));
                return null;
            }
            catch (InvalidDataException)
            {
                requireActive();
                steps.Add(new(Convert.ToHexString(request),
                    response is null ? null : Convert.ToHexString(response),
                    "FAILED", "INVALID_RESPONSE", timer.ElapsedMilliseconds));
                return null;
            }
        }

        // Discover standard PID support before reading any PID values.
        var pids = new SortedSet<byte>();
        for (int page = 0; page <= 0xE0 && !expired; page += 32)
        {
            var request = new byte[] { 1, (byte)page };
            ObdJ1979PidSupport.Page? decoded = null;
            var response = Read(request, data =>
            {
                decoded = ObdJ1979PidSupport.Decode(request, data);
                return string.Join(",", decoded.SupportedPids.Select(x => x.ToString("X2")));
            });
            if (response is null || decoded is null) break;
            foreach (var pid in decoded.SupportedPids.Where(x => x % 32 != 0)) pids.Add(pid);
            if (!decoded.HasNextPage) break;
        }
        foreach (var pid in pids)
        {
            if (expired) break;
            Read([1, pid]);
        }

        foreach (byte mode in new byte[] { 3, 7, 10 })
        {
            if (expired) break;
            Read([mode]);
        }

        // Frame 00 only: without a verified triggering DTC there is no
        // basis for reading a freeze-frame inventory.
        if (!expired)
        {
            var trigger = Read([2, 2, 0]);
            if (trigger is { Length: 5 } && (trigger[3] != 0 || trigger[4] != 0))
            {
                var frozen = new SortedSet<byte>();
                for (int page = 0; page <= 0xE0 && !expired; page += 32)
                {
                    var req = new byte[] { 2, (byte)page, 0 };
                    ObdJ1979PidSupport.Page? decoded = null;
                    if (Read(req, data =>
                    {
                        decoded = ObdJ1979PidSupport.Decode(req, data);
                        return string.Join(",", decoded.SupportedPids.Select(x => x.ToString("X2")));
                    }) is null || decoded is null) break;
                    foreach (var pid in decoded.SupportedPids.Where(x => x % 32 != 0 && x != 2))
                        frozen.Add(pid);
                    if (!decoded.HasNextPage) break;
                }
                foreach (var pid in frozen)
                {
                    if (expired) break;
                    Read([2, pid, 0]);
                }
            }
        }

        // CAN Mode 06 and Mode 08/09 availability share four-byte bitmaps
        // but each service has its own namespace and interpretation.
        SortedSet<byte> Inventory(byte mode)
        {
            var ids = new SortedSet<byte>();
            for (int page = 0; page <= 0xE0 && !expired; page += 32)
            {
                var req = new byte[] { mode, (byte)page };
                bool more = false;
                if (Read(req, data =>
                {
                    if (data.Length != 6 || data[1] != page ||
                        (page == 0xE0 && (data[5] & 1) != 0))
                        throw new InvalidDataException("Invalid support bitmap.");
                    for (int bit = 0; bit < 31; bit++)
                        if ((data[2 + bit / 8] & (0x80 >> (bit % 8))) != 0)
                            ids.Add((byte)(page + bit + 1));
                    more = (data[5] & 1) != 0;
                    return Convert.ToHexString(data.AsSpan(2));
                }) is null || !more) break;
            }
            return ids;
        }
        foreach (var mid in Inventory(6))
        {
            if (expired) break;
            Read([6, mid]);
        }
        Inventory(8); // Discovery only; never emit a control command.
        var info = Inventory(9);
        // VIN is independently attempted even if optional bitmap discovery
        // is absent. Do not infer the identity from a user-selected profile.
        if (!expired)
            Read([9, 2], data => vin = ObdJ1979Vin.DecodeIsoTp(data));
        foreach (var type in info.Where(x => x != 2))
        {
            if (expired) break;
            Read([9, type]);
        }
        requireActive();
        return new(vin, steps.ToArray(), !expired,
            ["MODE_04_CLEAR", "MODE_05_LEGACY", "MODE_08_CONTROL"]);
    }
}
