namespace CamaroDiag.J2534Bridge;

/// <summary>Explicit vehicle-wide functional requests, never a selected-ECU
/// write. The expected response set is operator supplied and checked against
/// live evidence. A silent ECU may still be affected; no claim of exhaustive
/// coverage. No automatic retry or generic actuator API.</summary>
internal static class ObdJ1979LegacyMutation
{
    internal enum Operation { Clear, EvapPreparation }
    internal sealed record Observation(string Stage, string RequestHex, string? TxHex,
        IReadOnlyList<ObdJ1979LegacyScan.Evidence> Replies);
    internal sealed record Result(string Outcome, string? Reason, string? ObservedVin,
        bool Attempted, IReadOnlyList<string> ExpectedSources,
        IReadOnlyDictionary<string,string> Acknowledgments,
        IReadOnlyDictionary<string,string[]?> BeforeConfirmed,
        IReadOnlyDictionary<string,string[]?> AfterConfirmed,
        IReadOnlyList<Observation> Observations, bool WholeVehicleCoverageVerified = false);

    internal static Result Run(Operation operation, string expectedVin, byte engineSource,
        IReadOnlyCollection<byte> documentedSources, bool warningAcknowledged, bool confirmedYes,
        Action requireActive, Func<byte[],int,ObdLegacyJ2534Transport.Exchange> read,
        Func<Operation,ObdLegacyJ2534Transport.Exchange> transmit)
    {
        if (!Enum.IsDefined(operation) || !warningAcknowledged || !confirmedYes ||
            !System.Text.RegularExpressions.Regex.IsMatch(expectedVin ?? "", @"\A[A-HJ-NPR-Z0-9]{17}\z") ||
            documentedSources.Count is < 1 or > 32 || documentedSources.Any(s => s is 0 or 0xF1 or 0xFF) ||
            documentedSources.Distinct().Count() != documentedSources.Count || !documentedSources.Contains(engineSource))
            throw new UnauthorizedAccessException("Explicit vehicle-wide scope, engine source, VIN and two confirmations required.");
        var expected = documentedSources.ToHashSet();
        string Key(byte s) => s.ToString("X2");
        var acknowledgments = expected.Order().ToDictionary(Key, _ => "NOT_TESTED");
        var before = expected.Order().ToDictionary(Key, _ => (string[]?)null);
        var after = expected.Order().ToDictionary(Key, _ => (string[]?)null);
        var observations = new List<Observation>();
        bool attempted = false;
        string? vin = null;
        Result Finish(string outcome, string? reason = null) => new(outcome, reason, vin, attempted,
            expected.Order().Select(Key).ToArray(), acknowledgments, before, after, observations.ToArray());

        byte[][] For(ObdLegacyJ2534Transport.Exchange x, byte source) =>
            x.Replies.Where(p => p.Source == source).Select(p => p.Payload).ToArray();
        void Record(string stage, byte[] request, ObdLegacyJ2534Transport.Exchange x) =>
            observations.Add(new(stage, Convert.ToHexString(request), x.TxHex,
                x.Replies.Select(p => new ObdJ1979LegacyScan.Evidence(Key(p.Source), p.RawHex,
                    Convert.ToHexString(p.Payload), p.Timestamp, p.InFrameResponseHex)).ToArray()));
        ObdLegacyJ2534Transport.Exchange Query(string stage, byte[] request)
        {
            requireActive();
            if (!ObdLegacyJ2534Transport.AllowsRead(request)) throw new InvalidOperationException("Invalid prerequisite.");
            var x = read(request, 1800);
            Record(stage, request, x);
            requireActive();
            if (x.Replies.Any(p => !expected.Contains(p.Source)))
                throw new InvalidDataException("Unexpected response source; vehicle scope differs.");
            return x;
        }
        bool Scope()
        {
            var x = Query("SCOPE", [1,0]);
            return expected.All(s =>
            {
                var rows = For(x,s);
                return rows.Length == 1 && rows[0].Length == 6 && rows[0][0] == 0x41 && rows[0][1] == 0;
            });
        }
        void Snapshot(string stage, Dictionary<string,string[]?> target)
        {
            foreach (byte mode in new byte[] { 3, 7, 10 })
            {
                var x = Query(stage,[mode]);
                if (mode != 3) continue;
                foreach (byte s in expected)
                {
                    var rows = For(x,s);
                    if (rows.Length > 0 && rows.All(r => r.Length == 7 && r[0] == 0x43))
                        target[Key(s)] = rows.SelectMany(r => ObdJ1979Dtc.DecodePairs(r.AsSpan(1)))
                            .Select(c => c.Display).Distinct().ToArray();
                }
            }
            Query(stage,[1,1]);
        }
        bool Zero(byte pid, byte[] positive)
        {
            var x = Query("STATIONARY",[1,pid]);
            var engine = For(x,engineSource);
            return engine.Length == 1 && engine[0].SequenceEqual(positive) &&
                x.Replies.All(p => p.Payload.SequenceEqual(positive) ||
                    p.Source != engineSource && p.Payload.Length == 3 && p.Payload[0] == 0x7F &&
                    p.Payload[1] == 1 && p.Payload[2] is 0x11 or 0x12 or 0x31);
        }
        try
        {
            if (!Scope()) return Finish("NOT_TESTED","RESPONDER_SET_UNVERIFIED");
            var counts = Query("IDENTITY",[9,1]);
            var fragments = Query("IDENTITY",[9,2]);
            foreach (byte s in expected)
            {
                var count = For(counts,s);
                var rows = For(fragments,s);
                if (count.Length == 0 && rows.Length == 0 && s != engineSource) continue;
                if (count.Length != 1 || !count[0].SequenceEqual(new byte[]{0x49,1,5}) ||
                    rows.Length != 5 || rows.Where((r,i) => r.Length != 7 || r[0] != 0x49 || r[1] != 2 || r[2] != i+1).Any())
                    return Finish("NOT_TESTED","VIN_SEQUENCE_UNVERIFIED");
                var data = rows.SelectMany(r => r.Skip(3)).ToArray();
                if (!data.AsSpan(0,3).SequenceEqual(new byte[3]) ||
                    !data.AsSpan(3).SequenceEqual(System.Text.Encoding.ASCII.GetBytes(expectedVin)))
                    return Finish("NOT_TESTED","VIN_MISMATCH");
                if (s == engineSource) vin = expectedVin;
            }
            if (vin is null) return Finish("NOT_TESTED","ENGINE_VIN_UNVERIFIED");
            var ackSources = expected.ToHashSet();
            if (operation == Operation.Clear)
            {
                Snapshot("BEFORE",before);
                // Some legacy ECUs may stay silent when no DTC is stored.
                // For an optional mutating test we refuse that ambiguity.
                if (before.Values.Any(x => x is null)) return Finish("NOT_TESTED","BASELINE_UNVERIFIED");
            }
            else
            {
                var x = Query("SUPPORT",[8,0,0,0,0,0,0]);
                ackSources.Clear();
                foreach (byte s in expected)
                {
                    var rows = For(x,s);
                    if (rows.Length == 0) continue; // no advertised support observed
                    if (rows.Length != 1 || rows[0].Length != 7 ||
                        !rows[0].AsSpan(0,3).SequenceEqual(new byte[]{0x48,0,0}))
                        return Finish("NOT_TESTED","CONTROL_SUPPORT_UNVERIFIED");
                    if ((rows[0][3]&0x80)!=0) ackSources.Add(s);
                }
                // Do not request preparation when any unexpected module also
                // advertises the TID. Even then scope remains functional.
                if (!ackSources.SetEquals(new[]{engineSource})) return Finish("NOT_TESTED","EVAP_TARGET_UNVERIFIED");
            }
            if (!Scope() || !Zero(0x0D,[0x41,0x0D,0]) || !Zero(0x0C,[0x41,0x0C,0,0]))
                return Finish("NOT_TESTED","STATIONARY_OR_SCOPE_UNVERIFIED");
            requireActive();
            attempted = true;
            byte[] request = operation == Operation.Clear ? [4] : [8,1,0,0,0,0,0];
            observations.Add(new("MUTATION",Convert.ToHexString(request),null,[]));
            var reply = transmit(operation);
            observations.RemoveAt(observations.Count-1);
            Record("MUTATION",request,reply);
            byte[] ack = operation == Operation.Clear ? [0x44] : [0x48,1,0,0,0,0,0];
            foreach (var group in reply.Replies.GroupBy(p => p.Source))
            {
                var rows = group.Select(p => p.Payload).ToArray();
                acknowledgments[Key(group.Key)] = rows.Length == 1 && rows[0].SequenceEqual(ack) ? "ACKNOWLEDGED" :
                    rows.Length == 1 && rows[0].Length == 3 && rows[0][0] == 0x7F &&
                    rows[0][1] == request[0] && rows[0][2] is not (0 or 0x78) ? $"REFUSED_{rows[0][2]:X2}" : "UNCERTAIN";
            }
            bool completeAck = reply.Replies.All(p => ackSources.Contains(p.Source)) &&
                ackSources.All(s => acknowledgments[Key(s)] == "ACKNOWLEDGED");
            if (operation == Operation.Clear)
            {
                Snapshot("AFTER",after);
                if (completeAck && after.Values.All(x => x is not null))
                    return Finish("ACKNOWLEDGED_READBACK",after.Values.Any(x => x!.Length > 0) ? "DTCS_STILL_PRESENT" : null);
            }
            else if (completeAck) return Finish("ACKNOWLEDGED","LEAK_TEST_NOT_PERFORMED");
            return Finish("UNCERTAIN","MISSING_OR_CONFLICTING_EVIDENCE");
        }
        catch (Exception error) when (error is IOException or InvalidDataException or TimeoutException or
            OperationCanceledException or InvalidOperationException)
        {
            return Finish(attempted ? "UNCERTAIN" : "NOT_TESTED",error.GetType().Name);
        }
    }
}
