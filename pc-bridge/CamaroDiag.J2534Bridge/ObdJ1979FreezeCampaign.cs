using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;
using CamaroDiagConnectors.Windows;

namespace CamaroDiag.J2534Bridge;

/// <summary>Explicit physical Mode 02 recipe. Additional frame numbers are
/// never guessed; their OEM values remain raw. A matching trigger is not an
/// atomic ECU snapshot. This path does not change the ordinary read policy.</summary>
internal static class ObdJ1979FreezeCampaign
{
    internal const int MaximumFrames = 8;
    internal const int MaximumPids = 64;
    internal const int MaximumResponse = 4095;
    internal const int MaximumSteps = MaximumFrames * (8 + 2 + 247) + 2;

    internal sealed record Plan(string ExpectedVin, int[] Frames, int[] SelectedPids, int BudgetMs = 180000)
    {
        internal Plan Validate()
        {
            if (!Regex.IsMatch(ExpectedVin ?? "", @"\A[A-HJ-NPR-Z0-9]{17}\z") ||
                Frames is null || Frames.Length is < 1 or > MaximumFrames ||
                Frames.Any(x => x is < 0 or > 255) || Frames.Distinct().Count() != Frames.Length ||
                SelectedPids is null || SelectedPids.Length > MaximumPids ||
                SelectedPids.Any(x => x is < 1 or > 255 || x == 2 || x % 32 == 0) ||
                SelectedPids.Distinct().Count() != SelectedPids.Length ||
                BudgetMs is < 1000 or > 600000)
                throw new ArgumentException("Invalid explicit freeze-frame plan.");
            return this with { Frames = Frames.ToArray(), SelectedPids = SelectedPids.ToArray() };
        }
    }

    internal sealed record Page(int Base, string BitmapHex, int[] AdvertisedPids, bool HasNextPage);
    internal sealed record Measurement(int Mode, int Pid, string Name, double Number, string Unit, int Frame);
    internal sealed record Read(string Outcome, byte[]? Response, long DurationMs, bool TransmissionAttempted);
    internal sealed record Step(string RequestHex, string? ResponseHex, string TransportOutcome,
        long DurationMs, bool TransmissionAttempted, string Status, string? Detail,
        Dictionary<string, object?>? Evidence);
    internal sealed record FrameResult(int Frame, bool RequiredFrame, string Status, string? TriggerDtc,
        string? FinalTriggerDtc, bool TriggerStable, bool InventoryComplete, Page[] VerifiedPages,
        int[] AdvertisedPids, int[] ReadPids, int[] UnadvertisedSelectedPids, int[] NotReadSelectedPids,
        int[] MeasurementSteps, string? Detail);
    internal sealed record Result(Plan Plan, string? InitialVin, string? FinalVin, bool Completed,
        string? StopReason, bool IdentityRechecked, FrameResult[] Frames, Step[] Steps,
        int[] UnvisitedFrames, bool AllFramesStable, long DurationMs)
    {
        public bool PhysicalVehicleVerified => false;
        public bool Authenticated => false;
        public bool WholeVehicleCoverageVerified => false;
        public bool PermitsProgramming => false;
    }

    private static readonly HashSet<int> ScalarPids =
    [
        0x04,0x05,0x0a,0x0c,0x0d,0x0e,0x0f,0x11,0x1f,0x21,0x22,
        0x23,0x2c,0x2d,0x2e,0x2f,0x30,0x31,0x32,0x33,0x3c,0x3d,
        0x3e,0x3f,0x42,0x43,0x45,0x46,0x47,0x48,0x49,0x4a,0x4b,
        0x4c,0x4d,0x4e,0x52,0x53,0x54,0x59,0x5a,0x5b,0x5c,
        0x5d,0x5e,0x61,0x62,0x63,0xa6
    ];
    private static readonly HashSet<string> Outcomes =
    ["RECEIVED","TIMEOUT","TRANSPORT_ERROR","BUDGET","CANCELLED","CONTEXT_CHANGED","LATE_RESPONSE"];

    internal static bool Allows(ReadOnlySpan<byte> request, IReadOnlyCollection<int> frames) =>
        request.SequenceEqual(new byte[] { 9, 2 }) ||
        request.Length == 3 && request[0] == 2 && frames.Contains((int)request[2]);

    private static string? DecodeVin(byte[] response)
    {
        if (response.Length != 20 || !response.AsSpan(0, 3).SequenceEqual(new byte[]{0x49,2,1})) return null;
        var vin = Encoding.ASCII.GetString(response, 3, 17);
        return Regex.IsMatch(vin, @"\A[A-HJ-NPR-Z0-9]{17}\z") ? vin : null;
    }

    private static string? Dtc(byte a, byte b) =>
        a == 0 && b == 0 ? null : $"{ "PCBU"[a >> 6]}{(a >> 4) & 3:X}{a & 15:X}{b:X2}";

    private static Page? Bitmap(int @base, ReadOnlySpan<byte> data)
    {
        if (data.Length != 4 || @base == 0xe0 && (data[3] & 1) != 0) return null;
        var pids = new List<int>();
        for (int bit = 0; bit < 31; bit++)
            if ((data[bit / 8] & (0x80 >> bit % 8)) != 0) pids.Add(@base + bit + 1);
        return new Page(@base, Convert.ToHexString(data), pids.ToArray(), (data[3] & 1) != 0);
    }

    internal static Step Classify(byte[] request, Read read)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (!Outcomes.Contains(read.Outcome) || read.DurationMs is < 0 or > 3600000 ||
            read.Response is { Length: 0 or > MaximumResponse } ||
            ((read.Outcome is "RECEIVED" or "LATE_RESPONSE") != (read.Response is not null)) ||
            ((read.Outcome is "RECEIVED" or "LATE_RESPONSE" or "TIMEOUT" or "TRANSPORT_ERROR") && !read.TransmissionAttempted) ||
            (read.Outcome == "BUDGET" && read.TransmissionAttempted))
            throw new InvalidDataException("Invalid freeze transport observation.");
        Step Row(string status, string? detail = null, Dictionary<string, object?>? evidence = null) =>
            new(Convert.ToHexString(request), read.Response is null ? null : Convert.ToHexString(read.Response),
                read.Outcome, read.DurationMs, read.TransmissionAttempted, status, detail, evidence);
        if (read.Outcome != "RECEIVED")
            return Row(read.Outcome == "TRANSPORT_ERROR" ? "FAILED" : "NOT_TESTED", read.Outcome);
        var response = read.Response!;
        if (response.Length == 3 && response[0] == 0x7f && response[1] == request[0] &&
            response[2] is not (0 or 0x78))
            return Row("NOT_TESTED", $"NRC_{response[2]:X2}");
        if (request.AsSpan().SequenceEqual(new byte[]{9,2}))
        {
            var vin = DecodeVin(response);
            return vin is null ? Row("FAILED","INVALID_VIN") :
                Row("PASSED", evidence: new() { ["vin"] = vin });
        }
        if (request.Length != 3 || request[0] != 2 || response.Length <= 3 ||
            response[0] != 0x42 || response[1] != request[1] || response[2] != request[2])
            return Row("FAILED","WRONG_SERVICE_PID_OR_FRAME");
        int pid = request[1], frame = request[2];
        var data = response.AsSpan(3);
        var evidence = new Dictionary<string, object?> {
            ["frame"] = frame, ["pid"] = pid, ["data_hex"] = Convert.ToHexString(data) };
        if (pid % 32 == 0)
        {
            var page = Bitmap(pid, data);
            if (page is null) return Row("FAILED","INVALID_SUPPORT_BITMAP");
            evidence["page"] = page;
            return Row("PASSED", evidence: evidence);
        }
        if (pid == 2)
        {
            if (data.Length != 2) return Row("FAILED","INVALID_TRIGGER_DTC");
            evidence["trigger_present"] = data[0] != 0 || data[1] != 0;
            evidence["trigger_dtc"] = Dtc(data[0], data[1]);
            return Row("PASSED", evidence: evidence);
        }
        if (frame != 0) return Row("RAW","STORED_RAW_VALUE",evidence);
        var normalized = J1979CampaignSemantics.DecodeObservation(request,response);
        if (normalized is null) return Row("FAILED","INVALID_PID_DATA_WIDTH");
        if (ScalarPids.Contains(pid) && normalized.Measurement is {} value)
        {
            evidence["measurement"] = new Measurement(value.Mode,value.Pid,value.Name,value.Number,value.Unit,frame);
            return Row("PASSED", evidence: evidence);
        }
        return Row("RAW","STORED_RAW_VALUE",evidence);
    }

    internal static Result Execute(Plan requested, Func<byte[], Read> observe, Func<long>? elapsed = null)
    {
        var plan = requested.Validate();
        var steps = new List<Step>();
        var results = new List<FrameResult>();
        string? stop = null, firstVin = null, finalVin = null;

        Step? ReadRequest(byte[] request)
        {
            if (stop is not null) return null;
            if (!Allows(request, plan.Frames) || steps.Count >= MaximumSteps)
                throw new InvalidOperationException("Unexpected freeze-frame request.");
            var step = Classify(request, observe(request));
            steps.Add(step);
            if (step.TransportOutcome is "BUDGET" or "LATE_RESPONSE") stop = "BUDGET_EXHAUSTED";
            else if (step.TransportOutcome is "TRANSPORT_ERROR" or "CANCELLED" or "CONTEXT_CHANGED")
                stop = step.TransportOutcome;
            return step;
        }

        Dictionary<string,object?>? Evidence(Step? step) => step?.Status == "PASSED" ? step.Evidence : null;

        FrameResult Acquire(int frame)
        {
            string? triggerDtc = null, finalTrigger = null;
            bool stable = false, complete = false;
            var ids = new SortedSet<int>();
            var pages = new List<Page>();
            var readPids = new List<int>();
            var missing = new SortedSet<int>();
            var notRead = new SortedSet<int>();
            var indexes = new List<int>();
            var selected = plan.SelectedPids.ToHashSet();

            FrameResult Finish(string status,string? detail = null) =>
                new(frame,frame==0,status,triggerDtc,finalTrigger,stable,complete,pages.ToArray(),
                    ids.ToArray(),readPids.ToArray(),missing.ToArray(),notRead.ToArray(),indexes.ToArray(),detail);

            Page? ReadPage(int @base)
            {
                var evidence = Evidence(ReadRequest([2,(byte)@base,(byte)frame]));
                if (evidence is null) return null;
                var page = (Page)evidence["page"]!;
                pages.Add(page);
                ids.UnionWith(page.AdvertisedPids);
                return page;
            }

            var initial = ReadPage(0);
            if (initial is null) return Finish("UNAVAILABLE","PID00_UNVERIFIED");
            if (!ids.Contains(2)) return Finish("UNAVAILABLE","TRIGGER_NOT_ADVERTISED");
            var trigger = Evidence(ReadRequest([2,2,(byte)frame]));
            if (trigger is null) return Finish("UNAVAILABLE","TRIGGER_UNVERIFIED");
            triggerDtc = (string?)trigger["trigger_dtc"];
            if (trigger["trigger_present"] is false) return Finish("ABSENT","ZERO_TRIGGER_DTC");

            bool continuation = initial.HasNextPage;
            complete = !continuation;
            for (int @base = 32; @base < 256 && continuation && stop is null; @base += 32)
            {
                var page = ReadPage(@base);
                if (page is null) break;
                continuation = page.HasNextPage;
                complete = !continuation;
            }
            var candidates = ids.Where(pid => selected.Count == 0 ? pid != 2 : selected.Contains(pid)).ToArray();
            if (complete) missing.UnionWith(selected.Except(ids));
            else notRead.UnionWith(selected.Except(ids));
            int failures = 0;
            foreach (var pid in candidates)
            {
                var step = ReadRequest([2,(byte)pid,(byte)frame]);
                if (step is null) break;
                if (step.TransmissionAttempted) readPids.Add(pid);
                indexes.Add(steps.Count - 1);
                if (step.Status is not ("PASSED" or "RAW")) failures++;
            }
            notRead.UnionWith(selected.Intersect(ids).Except(readPids));
            if (stop is not null) return Finish("PARTIAL",stop);
            var final = Evidence(ReadRequest([2,2,(byte)frame]));
            finalTrigger = final is null ? null : (string?)final["trigger_dtc"];
            stable = final is not null && finalTrigger == triggerDtc;
            if (!stable) return final is null ? Finish("PARTIAL","FINAL_TRIGGER_UNVERIFIED") :
                Finish("CHANGED","TRIGGER_CHANGED");
            return !complete || failures > 0 || readPids.Count != candidates.Length
                ? Finish("PARTIAL","INCOMPLETE_INVENTORY_OR_VALUES") : Finish("COMPLETE");
        }

        firstVin = Evidence(ReadRequest([9,2]))?.GetValueOrDefault("vin") as string;
        if (stop is null && firstVin != plan.ExpectedVin)
            stop = firstVin is null ? "VIN_UNVERIFIED" : "VIN_MISMATCH";
        foreach (var frame in plan.Frames)
        {
            if (stop is not null) break;
            results.Add(Acquire(frame));
        }
        if (stop is null)
        {
            finalVin = Evidence(ReadRequest([9,2]))?.GetValueOrDefault("vin") as string;
            if (stop is null && finalVin != firstVin)
                stop = finalVin is null ? "FINAL_VIN_UNVERIFIED" : "FINAL_VIN_MISMATCH";
        }
        bool completed = stop is null && results.Count == plan.Frames.Length;
        return new(plan,firstVin,finalVin,completed,stop,completed && firstVin == finalVin && firstVin == plan.ExpectedVin,
            results.ToArray(),steps.ToArray(),plan.Frames.Skip(results.Count).ToArray(),
            completed && results.All(row => row.Status is "COMPLETE" or "ABSENT"),elapsed?.Invoke() ?? 0);
    }

    internal static Result Run(Plan requested, Func<byte[],int,byte[]> exchange, Action requireCurrent,
        Func<long>? monotonicMs = null,Func<byte[],int>? requestTimeout=null)
    {
        var plan = requested.Validate();
        var watch = Stopwatch.StartNew();
        var clock = monotonicMs ?? (() => watch.ElapsedMilliseconds);
        long start = clock();
        long Elapsed() => Math.Max(0,clock()-start);
        Read Observe(byte[] request)
        {
            long begin = Elapsed();
            bool attempted = false;
            Read Observation(string outcome,byte[]? response = null) =>
                new(outcome,response,Math.Max(0,Elapsed()-begin),attempted);
            try
            {
                requireCurrent();
                long left = plan.BudgetMs - begin;
                // The J2534 implementation clamps each request to at least 100ms.
                if (left < 100) return Observation("BUDGET");
                attempted = true;
                int maximum=requestTimeout?.Invoke(request)??2500;
                if(maximum is <100 or >20000)throw new ArgumentException("Invalid logical read deadline");
                var response = exchange(request,(int)Math.Min(maximum,left));
                if (response is null || response.Length is 0 or > MaximumResponse) return Observation("TRANSPORT_ERROR");
                requireCurrent();
                return Elapsed() >= plan.BudgetMs ? Observation("LATE_RESPONSE",response) : Observation("RECEIVED",response);
            }
            catch (OperationCanceledException) { return Observation("CANCELLED"); }
            catch (TimeoutException) { return Observation(attempted ? "TIMEOUT" : "CONTEXT_CHANGED"); }
            catch (Exception e) when (e is IOException or InvalidDataException or InvalidOperationException or ArgumentException)
            { return Observation(attempted ? "TRANSPORT_ERROR" : "CONTEXT_CHANGED"); }
        }
        return Execute(plan,Observe,Elapsed);
    }
}
