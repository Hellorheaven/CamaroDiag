using System.Diagnostics;
using CamaroDiagConnectors.Windows;

namespace CamaroDiag.J2534Bridge;

/// <summary>Independent, read-only J1979 physical CAN recipe. One selected
/// responder, explicit request/response evidence, no simulated fallback.
/// The exchange delegate is responsible for transport/address correlation.</summary>
internal static class ObdJ1979FunctionalScan
{
    internal sealed record Step(string RequestHex, string? ResponseHex,
        string Status, string? Detail, long DurationMs,
        J1979CampaignSemantics.Observation? ApplicationEvidence = null);
    internal sealed record Result(string? ObservedVin, IReadOnlyList<Step> Steps,
        bool Completed, string[] NotExecuted, string? StopReason, string? DeclaredVin=null)
    {
        public string VinMatch => DeclaredVin is null ? "NOT_REQUESTED" :
            ObservedVin is null ? "UNVERIFIED" :
            ObservedVin==DeclaredVin ? "MATCH" : "MISMATCH";
        public bool Cancelled => StopReason=="CANCELLED";
        public IReadOnlyList<InventorySummary> Inventory => SummarizeInventory(Steps);
        public IReadOnlyList<DtcModeSummary> DtcModes => new[]{3,7,10}.Select(mode=>{
            var step=Steps.SingleOrDefault(s=>s.RequestHex==mode.ToString("X2"));
            return new DtcModeSummary(mode,step?.ApplicationEvidence?.DtcCodes,step?.Status);
        }).ToArray();
        // Keep the native CJ/1 evidence schema unchanged; this is a derived
        // engineering view of correlated physical CAN Mode 06 scan records.
        public IReadOnlyList<ObdJ1979ExtendedVehicleInfo.Record> ExtendedVehicleInfo => Steps
            .Where(s=>s.Status=="RAW" && s.Detail=="EXTENDED_INFOTYPE_DECODED" &&
                s.ResponseHex is not null)
            .Select(s=>ObdJ1979ExtendedVehicleInfo.Can(
                Convert.ToByte(s.RequestHex[2..],16),Convert.FromHexString(s.ResponseHex!))).ToArray();
        public IReadOnlyList<ObdJ1979Mode06.Test> MonitorTests => Steps
            .Where(s=>s.Status=="PASSED" && s.RequestHex.Length==4 &&
                s.RequestHex.StartsWith("06", StringComparison.Ordinal) &&
                Convert.ToByte(s.RequestHex[2..],16)%32!=0 && s.ResponseHex is not null)
            .SelectMany(s=>ObdJ1979Mode06.DecodeTests(
                Convert.FromHexString(s.RequestHex),Convert.FromHexString(s.ResponseHex!)))
            .ToArray();
        public IReadOnlyList<J1979CampaignSemantics.Measurement> Measurements => Steps
            .Where(s=>s.Status=="PASSED").Select(s=>s.ApplicationEvidence?.Measurement)
            .OfType<J1979CampaignSemantics.Measurement>().ToArray();
    }
    internal sealed record InventorySummary(int Mode,string State,IReadOnlyList<int> AdvertisedIds,int VerifiedPages);
    internal sealed record DtcModeSummary(int Mode,IReadOnlyList<string>? Codes,string? Status);
    private static IReadOnlyList<InventorySummary> SummarizeInventory(IReadOnlyList<Step> steps)
    {
        var summaries=new List<InventorySummary>();
        foreach(int mode in new[]{1,2,6,8,9}){
            var trigger=steps.SingleOrDefault(s=>s.RequestHex=="020200")?.ApplicationEvidence?.FreezeTrigger;
            if(mode==2&&trigger is {Present:false}){
                summaries.Add(new(mode,"NOT_APPLICABLE",[],0));continue;
            }
            var ids=new SortedSet<int>();int count=0;bool complete=false;
            for(int page=0;page<=0xE0;page+=32){
                string req=mode.ToString("X2")+page.ToString("X2")+(mode==2?"00":"");
                var row=steps.SingleOrDefault(s=>s.RequestHex==req);
                var evidence=row?.ApplicationEvidence?.SupportedPage;
                if(row?.Status!="PASSED"||evidence is null||evidence.Mode!=mode||evidence.Base!=page)break;
                ids.UnionWith(evidence.SupportedIds);count++;
                if(!evidence.HasNextPage){complete=true;break;}
            }
            summaries.Add(new(mode,complete?"COMPLETE":count>0?"PARTIAL":"UNAVAILABLE",ids.ToArray(),count));
        }
        return summaries.AsReadOnly();
    }

    internal static Result Run(Func<byte[], int, byte[]> exchange,
        Action requireActive, TimeSpan budget, bool retainCancelledEvidence=false, string? expectedVin=null)
    {
        ArgumentNullException.ThrowIfNull(exchange);
        ArgumentNullException.ThrowIfNull(requireActive);
        if (budget <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(budget));
        if(expectedVin is not null && !System.Text.RegularExpressions.Regex.IsMatch(expectedVin,@"\A[A-HJ-NPR-Z0-9]{17}\z"))
            throw new ArgumentException("Invalid expected VIN.",nameof(expectedVin));
        var watch = Stopwatch.StartNew();
        var steps = new List<Step>();
        string? vin = null;
        bool expired = false;
        string? stopReason = null;

        byte[]? Read(byte[] request, Func<byte[], string>? decode = null)
        {
            requireActive();
            if (!StandardObdPhysicalPolicy.AllowsRead(request))
                throw new InvalidOperationException("Non-read request in physical recipe.");
            var remaining = budget - watch.Elapsed;
            if (remaining.TotalMilliseconds < 100)
            {
                expired = true;
                stopReason = "BUDGET_EXHAUSTED";
                steps.Add(new(Convert.ToHexString(request), null, "NOT_TESTED",
                    stopReason, 0));
                return null;
            }
            var timer = Stopwatch.StartNew();
            byte[]? response = null;
            try
            {
                response = exchange(request, (int)Math.Min(2500, remaining.TotalMilliseconds));
                requireActive();
                if(watch.Elapsed >= budget){
                    expired=true;
                    stopReason="BUDGET_EXHAUSTED";
                    steps.Add(new(Convert.ToHexString(request),Convert.ToHexString(response),
                        "NOT_TESTED","LATE_RESPONSE",timer.ElapsedMilliseconds));
                    return null;
                }
                var reply = ObdJ1979Transaction.Decode(request, response,
                    expectIdentifierEcho: request.Length >= 2);
                var application=J1979CampaignSemantics.DecodeObservation(request,response);
                if(application is null)throw new InvalidDataException("Invalid application record.");
                if (reply.Kind == ObdJ1979Transaction.ReplyKind.Negative)
                {
                    steps.Add(new(Convert.ToHexString(request), Convert.ToHexString(response),
                        "NOT_TESTED", $"NRC_{reply.NegativeResponseCode:X2}", timer.ElapsedMilliseconds,application));
                    return null;
                }
                // Unknown records must still carry data beyond the echoed
                // identifier (and frame number for Mode 02).
                if (request[0] is 1 or 2 or 9 &&
                    response.Length <= (request[0] == 2 ? 3 : 2))
                    throw new InvalidDataException("Missing diagnostic record data.");
                if(application.Shape=="RAW"){
                    string? detail = null;
                    if(request[0]==9 && ObdJ1979ExtendedVehicleInfo.IsExtended(request[1])){
                        _=ObdJ1979ExtendedVehicleInfo.Can(request[1],response);
                        detail="EXTENDED_INFOTYPE_DECODED";
                    }
                    steps.Add(new(Convert.ToHexString(request),Convert.ToHexString(response),
                        "RAW",detail,timer.ElapsedMilliseconds,application));
                    return null;
                }
                var detail = decode is null
                    ? ObdJ1979Interpretation.Interpret(request, response).Description
                    : decode(response);
                steps.Add(new(Convert.ToHexString(request), Convert.ToHexString(response),
                    "PASSED", detail, timer.ElapsedMilliseconds,application));
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
            catch (IOException error)
            {
                requireActive();
                expired = true; // Stop after a terminal transport fault; no retry.
                stopReason = "TRANSPORT_ERROR";
                steps.Add(new(Convert.ToHexString(request),
                    response is null ? null : Convert.ToHexString(response),
                    "FAILED", stopReason + ":" + error.GetType().Name,
                    timer.ElapsedMilliseconds));
                return null;
            }
        }

        try {
        // Prioritize VIN and DTC evidence before the potentially large
        // supported-PID inventory consumes the campaign budget.
        Read([9, 2], data => vin = ObdJ1979Vin.DecodeIsoTp(data));
        if(!expired && expectedVin is not null && vin!=expectedVin){
            expired=true;
            stopReason=vin is null?"VIN_UNVERIFIED":"VIN_MISMATCH";
        }
        foreach (byte mode in new byte[] { 3, 7, 10 })
        {
            if (expired) break;
            Read([mode]);
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
        // VIN was independently attempted before the inventory.
        foreach (var type in info.Where(x => x != 2))
        {
            if (expired) break;
            Read([9, type]);
        }
        requireActive();
        } catch(OperationCanceledException) when(retainCancelledEvidence) {
            expired=true;
            stopReason="CANCELLED";
        }
        return new(vin, steps.ToArray(), !expired,
            ["MODE_04_CLEAR", "MODE_05_LEGACY", "MODE_08_CONTROL"], stopReason,expectedVin);
    }
}
