namespace CamaroDiag.J2534Bridge;

/// <summary>Independent, explicitly authorized physical emissions reset evidence.
/// Never invoked from the read-only scan. Permanent DTCs are observed, not
/// assumed cleared. A positive ACK is distinct from verified readback.</summary>
internal static class ObdJ1979ClearRecipe
{
    internal sealed record Observation(string Stage, string RequestHex, string? ResponseHex,
        string? Interpretation, string? Error);
    internal sealed record Result(string Outcome, bool CommandAttempted, bool Acknowledged,
        string? ObservedVin, string? Reason, IReadOnlyList<Observation> Observations,
        IReadOnlyDictionary<string, string[]?> BeforeDtcs, IReadOnlyDictionary<string, string[]?> AfterDtcs);

    internal static Result Run(string expectedVin, bool primaryPhysicalChannel,
        bool warningAcknowledged, bool confirmedYes, Action requireCurrent,
        Func<byte[], int, byte[]> read, Func<byte[]> transmitApprovedReset, Action<Result> saveBaseline)
    {
        ArgumentNullException.ThrowIfNull(saveBaseline);
        if (!primaryPhysicalChannel || !warningAcknowledged || !confirmedYes ||
            !System.Text.RegularExpressions.Regex.IsMatch(expectedVin ?? "", @"\A[A-HJ-NPR-Z0-9]{17}\z"))
            throw new UnauthorizedAccessException("Physical ECM, VIN and two confirmations required.");
        var observations = new List<Observation>();
        var before = new Dictionary<string, string[]?> { ["03"] = null, ["07"] = null, ["0A"] = null };
        var after = new Dictionary<string, string[]?> { ["03"] = null, ["07"] = null, ["0A"] = null };
        string? vin = null;
        bool attempted = false, acknowledged = false;
        Result Finish(string outcome, string? reason = null) => new(outcome, attempted,
            acknowledged, vin, reason, observations.ToArray(), new Dictionary<string,string[]?>(before), new Dictionary<string,string[]?>(after));
        byte[] Query(string stage, byte[] request, Func<byte[], string> decode)
        {
            requireCurrent();
            if (!StandardObdPhysicalPolicy.AllowsRead(request)) throw new InvalidOperationException();
            string hex = Convert.ToHexString(request);
            int index = observations.Count;
            observations.Add(new(stage, hex, null, null, null));
            try
            {
                var response = read(request, 1800);
                observations[index] = new(stage, hex, Convert.ToHexString(response), null, null);
                requireCurrent();
                var interpretation = decode(response);
                observations[index] = observations[index] with { Interpretation = interpretation };
                return response;
            }
            catch (Exception error)
            {
                observations[index] = observations[index] with { Error = error.GetType().Name };
                throw;
            }
        }
        void Snapshot(string stage, Dictionary<string, string[]?> target)
        {
            foreach (byte mode in new byte[] { 3, 7, 10 })
            {
                string key = mode.ToString("X2");
                try
                {
                    Query(stage, [mode], response =>
                    {
                        var reply = ObdJ1979Transaction.Decode([mode], response);
                        if (reply.Kind == ObdJ1979Transaction.ReplyKind.Negative)
                            return $"NRC_{reply.NegativeResponseCode:X2}";
                        target[key] = ObdJ1979Dtc.DecodeCanCounted(reply.Data)
                            .Select(code => code.Display).ToArray();
                        return string.Join(",", target[key]!);
                    });
                }
                catch (TimeoutException) { /* unavailable is not zero */ }
                catch (InvalidDataException) { /* malformed is not zero */ }
            }
            try
            {
                Query(stage, [1, 1], response =>
                    ObdJ1979MonitorStatus.Format(ObdJ1979MonitorStatus.Decode(response)));
            }
            catch (TimeoutException) { }
            catch (InvalidDataException) { }
        }

        try
        {
            Query("PRECONDITION", [9, 2], response => vin = ObdJ1979Vin.DecodeIsoTp(response));
            if (vin != expectedVin) return Finish("NOT_TESTED", "VIN_MISMATCH");
            Snapshot("BEFORE", before);
            // Require at least a real confirmed-DTC baseline. Optional modes
            // and readiness may be unsupported, never silently zero.
            if (before["03"] is null) return Finish("NOT_TESTED", "BASELINE_UNAVAILABLE");
            var speed = Query("PRECONDITION", [1, 0x0D], Convert.ToHexString);
            var rpm = Query("PRECONDITION", [1, 0x0C], Convert.ToHexString);
            if (!StandardObdPhysicalPolicy.CanClear(true, primaryPhysicalChannel, speed, rpm))
                return Finish("NOT_TESTED", "STATIONARY_EVIDENCE_REQUIRED");
            saveBaseline(Finish("BASELINE_SAVED"));
            requireCurrent();
            var freshVin = ObdJ1979Vin.DecodeIsoTp(Query("RECHECK", [9,2], Convert.ToHexString));
            if (freshVin != vin) return Finish("NOT_TESTED","IDENTITY_CHANGED_AFTER_BASELINE");
            speed = Query("RECHECK", [1,0x0D], Convert.ToHexString);
            rpm = Query("RECHECK", [1,0x0C], Convert.ToHexString);
            if (!StandardObdPhysicalPolicy.CanClear(true,primaryPhysicalChannel,speed,rpm))
                return Finish("NOT_TESTED","STATIONARY_EVIDENCE_CHANGED");
            requireCurrent();
            attempted = true;
            observations.Add(new("RESET", "04", null, null, null));
            byte[] ack = transmitApprovedReset();
            observations[^1] = new("RESET", "04", Convert.ToHexString(ack), null, null);
            if (!StandardObdPhysicalPolicy.IsClearAcknowledged(ack))
            {
                if (ack.Length == 3 && ack[0] == 0x7F && ack[1] == 4 && ack[2] is not (0 or 0x78))
                    return Finish("REFUSED", $"NRC_{ack[2]:X2}");
                return Finish("UNCERTAIN", "INVALID_OR_INCOMPLETE_ACK");
            }
            acknowledged = true;
            Snapshot("AFTER", after);
            if (after["03"] is null || after["07"] is null)
                return Finish("ACKNOWLEDGED_PARTIAL", "READBACK_UNAVAILABLE");
            return Finish("ACKNOWLEDGED_READBACK", after["03"]!.Length + after["07"]!.Length == 0
                ? null : "DTCS_STILL_PRESENT");
        }
        catch (OperationCanceledException) when (!attempted) { throw; }
        catch (Exception error) when (error is IOException or TimeoutException or InvalidDataException
            or OperationCanceledException or InvalidOperationException)
        {
            return Finish(acknowledged ? "ACKNOWLEDGED_PARTIAL" :
                attempted ? "UNCERTAIN" : "NOT_TESTED", error.GetType().Name);
        }
    }
}
