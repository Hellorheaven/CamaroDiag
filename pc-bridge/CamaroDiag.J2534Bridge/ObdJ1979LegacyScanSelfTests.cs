namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979LegacyScanSelfTests
{
    internal static int Run()
    {
        int checks = 0;
        void Check(bool value, string label)
        {
            checks++;
            if (!value) throw new InvalidOperationException("Legacy J1979: " + label);
        }
        void Reject(Action action, string label)
        {
            try { action(); }
            catch (Exception error) when (error is ArgumentException or InvalidDataException)
            { checks++; return; }
            throw new InvalidOperationException("Expected rejection: " + label);
        }
        usingScope();
        void usingScope()
        {
            const byte source = 0x10;
            var vpw = ObdLegacyJ2534Transport.Variant.Vpw;
            var pwm = ObdLegacyJ2534Transport.Variant.Pwm;
            var slow = ObdLegacyJ2534Transport.Variant.KwpSlow;
            var fast = ObdLegacyJ2534Transport.Variant.KwpFast;
            var iso = ObdLegacyJ2534Transport.Variant.Iso9141;
            Check(ObdLegacyJ2534Transport.Profile(pwm) == (2u, 41600u), "PWM J2534 ID");
            Check(ObdLegacyJ2534Transport.Profile(vpw) == (1u, 10400u), "VPW J2534 ID");
            Check(ObdLegacyJ2534Transport.Profile(iso) == (3u, 10400u), "ISO9141 J2534 ID");
            Check(ObdLegacyJ2534Transport.Profile(slow) == (4u, 10400u) &&
                ObdLegacyJ2534Transport.Profile(fast) == (4u, 10400u), "KWP J2534 ID");
            Check(Convert.ToHexString(ObdLegacyJ2534Transport.FrameRead(pwm, [1, 0])) == "616AF10100", "PWM TX header");
            Check(Convert.ToHexString(ObdLegacyJ2534Transport.FrameRead(vpw, [1, 0])) == "686AF10100", "VPW TX header");
            Check(Convert.ToHexString(ObdLegacyJ2534Transport.FrameRead(iso, [1, 0])) == "686AF10100", "ISO9141 TX header");
            Check(Convert.ToHexString(ObdLegacyJ2534Transport.FrameRead(fast, [1, 0])) == "C233F10100", "KWP functional TX header");
            Reject(() => ObdLegacyJ2534Transport.FrameRead(vpw, [4]), "clear denied in read recipe");
            Reject(() => ObdLegacyJ2534Transport.FrameRead(vpw, [8, 1, 0, 0, 0, 0, 0]), "control denied");
            Reject(() => ObdLegacyJ2534Transport.FrameRead(vpw, [8, 0]), "CAN format denied");
            byte[] vpwResponse = [0x48, 0x6B, source, 0x41, 0, 0x80, 0, 0, 0];
            var frame = new J2534StandardChannel.Frame(1, 0, 100, vpwResponse);
            var packet = ObdLegacyJ2534Transport.Decode(vpw, source, frame);
            Check(packet is not null && packet.Source == source &&
                Convert.ToHexString(packet.Payload) == "410080000000", "exact VPW responder");
            Check(ObdLegacyJ2534Transport.Decode(vpw, 0x11, frame) is null, "other responder rejected");
            Check(ObdLegacyJ2534Transport.Decode(pwm, source, frame) is null, "other protocol rejected");
            Check(ObdLegacyJ2534Transport.Decode(vpw, source, frame with { RxStatus = 1 }) is null, "TX echo rejected");
            var kwp = new J2534StandardChannel.Frame(4, 0, 1, [0x86, 0xF1, source, 0x41, 0, 0x80, 0, 0, 0]);
            Check(ObdLegacyJ2534Transport.Decode(fast, source, kwp)?.Payload.Length == 6, "KWP length and physical response");
            Reject(() => ObdLegacyJ2534Transport.Decode(fast, source, kwp with { Data = [0x87,0xF1,source,0x41,0] }), "KWP invalid length");
            byte[] pwmWithIfr = [0x41,0x6B,source,0x41,0,0x80,0,0,0,0xF1];
            var withIfr = J2534StandardChannel.DecodeReceiveData(2, 0, 2, pwmWithIfr, 10, 9, true);
            var pwmPacket = ObdLegacyJ2534Transport.Decode(pwm, source, withIfr);
            Check(pwmPacket is { InFrameResponseHex: "F1" } && pwmPacket.Payload.Length == 6,
                "PWM IFR never interpreted as payload");
            Reject(() => J2534StandardChannel.DecodeReceiveData(2, 0, 2, pwmWithIfr, 10, 9), "opt-in IFR only");
            Reject(() => J2534StandardChannel.DecodeReceiveData(1, 0, 2, pwmWithIfr, 10, 9, true), "IFR not allowed for VPW");
            Reject(() => J2534StandardChannel.DecodeReceiveData(2, 0, 2, pwmWithIfr, 10, 11, true), "extra offset bounded");

            const string vin = "1G1FA1RXXR0123456";
            byte[] vinBytes = [0,0,0,..System.Text.Encoding.ASCII.GetBytes(vin)];
            byte[][] vinRows = vinBytes.Chunk(4).Select((chunk,i) =>
                new byte[] {0x49, 2, (byte)(i+1), ..chunk}).ToArray();
            var sent = new List<string>();
            var replies = new Dictionary<string, byte[][]>
            {
                ["0100"] = [[0x41,0,0x80,0,0x20,0]],
                ["0900"] = [[0x49,0,1,0xC0,0,0,0]],
                ["0901"] = [[0x49,1,5]], ["0902"] = vinRows,
                ["03"] = [[0x43,1,0x23,0,0,0,0]], ["07"] = [[0x47,0,0,0,0,0,0]],
                ["0A"] = [], ["0101"] = [[0x41,1,0,0,0,0]], ["0113"] = [[0x41,0x13,1]],
                ["020200"] = [[0x42,2,0,0,0]], ["050001"] = [[0x45,0,1,0x80,0,0,0]],
                ["050101"] = [[0x45,1,1,0x2A]], ["0600"] = [[0x46,0,0xFF,0x80,0,0,0]],
                ["0601"] = [[0x46,1,0x81,0,1,0,2]], ["08000000000000"] = [[0x48,0,0,0x80,0,0,0]]
            };
            byte packetSource = source;
            string? failAt = null;
            var cancel = false;
            ObdLegacyJ2534Transport.Exchange Exchange(byte[] request, int timeout)
            {
                var hex = Convert.ToHexString(request);
                sent.Add(hex);
                Check(timeout is >= 1 and <= 1800, "bounded request");
                if (hex == failAt) throw new IOException("driver lost");
                return new(Convert.ToHexString(ObdLegacyJ2534Transport.FrameRead(vpw, request)),
                    replies[hex].Select((data,i) => new ObdLegacyJ2534Transport.Packet(packetSource,
                        Convert.ToHexString(new byte[] {0x48,0x6B,packetSource,..data}), data, (uint)i)).ToArray());
            }
            ObdJ1979LegacyScan.Result Run() => ObdJ1979LegacyScan.Run(source, Exchange,
                () => { if (cancel) throw new OperationCanceledException(); }, TimeSpan.FromSeconds(10));
            var complete = Run();
            Check(complete.TransportObserved && complete.Completed && complete.ObservedVin == vin, "complete selected ECU recipe");
            Check(sent.ToHashSet().SetEquals(replies.Keys) && sent.Distinct().Count() == sent.Count, "only advertised reads");
            Check(complete.Steps.Single(x => x.RequestHex == "03").Detail == "P0123", "legacy DTC without count");
            Check(complete.DtcModes.Single(x=>x.Mode==3).Codes!.SequenceEqual(new[]{"P0123"})&&
                complete.DtcModes.Single(x=>x.Mode==7).Codes!.Count==0&&complete.DtcModes.Single(x=>x.Mode==10).Codes is null,
                "Structured DTC evidence distinguishes empty from unavailable");
            Check(complete.Inventory.Single(x=>x.Mode==1).AdvertisedIds.SequenceEqual(new byte[]{1,19})&&
                complete.Inventory.Single(x=>x.Mode==2).State=="NOT_APPLICABLE"&&
                complete.Inventory.Single(x=>x.Mode==8).AdvertisedIds.SequenceEqual(new byte[]{1}),
                "Independent structured service inventories");
            Check(complete.Readiness is {MilOn:false}&&complete.FreezeFrameTrigger is {Present:false},
                "Structured readiness and absent freeze trigger");
            Check(complete.IdentificationRecords.Single(x=>x.InfoType==2).Values.Single()==vin,
                "Structured selected-source identity");
            Check(complete.MonitorRecords.Single(x=>x.Mode==5).EncodedDataHex=="2A"&&
                complete.MonitorRecords.Single(x=>x.Mode==6) is {ValueEncoded:1,LimitEncoded:2},
                "Monitor encoded values retained without verdict");
            var legacyMonitors=ObdJ1979LegacyMonitorValues.Project(complete.Steps,complete.SelectedResponseSource);
            Check(legacyMonitors.Count==2,"Both legacy monitor services projected");
            Check(legacyMonitors.Single(x=>x.Record.Mode==5).Record.Scaled is
                { Unit:"V",Minimum:null,Maximum:null } oxygen &&
                Math.Abs(oxygen.Value-0.21d)<1e-9,"Mode 05 constant conversion");
            var rawMonitor=ObdJ1979LegacyMonitorValues.Decode([6,1],[0x46,1,0x81,0,1,0,2]);
            Check(rawMonitor is {ComponentId:1,LimitType:"MINIMUM",EncodedRelation:"BELOW",Scaled:null},
                "Mode 06 legacy component and limit direction, no guessed unit");
            Check(ObdJ1979LegacyMonitorValues.Decode([5,0x70,1],[0x45,0x70,1,1,2,3]) is {Scaled:null},
                "Manufacturer oxygen test retained raw");
            Check(ObdJ1979LegacyMonitorValues.Decode([5,1,1],[0x45,1,2,42]) is null,
                "Wrong oxygen sensor rejected");
            // Diesel IPT is an odd-numbered data type, not a count. The
            // shared count is InfoType 07, and only the selected source counts.
            replies["0900"]=[[0x49,0,1,0xC2,0x20,0,0]];
            replies["0907"]=[[0x49,7,9]];
            byte[] dieselBytes=Enumerable.Range(0,18).SelectMany(n=>new byte[]{0,(byte)n}).ToArray();
            replies["090B"]=dieselBytes.Chunk(4).Select((chunk,i)=>
                new byte[]{0x49,11,(byte)(i+1),..chunk}).ToArray();
            sent.Clear();
            var diesel=Run().IdentificationRecords.Single(x=>x.InfoType==11).Extended;
            Check(diesel is {CounterCount:18,GeneralDenominator:0,IgnitionCycles:1} &&
                diesel.Monitors!.Last().Name=="fuel-system","Legacy diesel data reconstructed using 07 count");
            var malformedDiesel=replies["090B"];
            replies["090B"]=malformedDiesel.Reverse().ToArray();
            var invalidDiesel=Run();
            Check(invalidDiesel.IdentificationRecords.All(x=>x.InfoType!=11)&&
                invalidDiesel.Steps.Single(x=>x.RequestHex=="090B").Status=="FAILED",
                "Unordered legacy counter fragments rejected");
            replies.Remove("0907");replies.Remove("090B");
            replies["0900"]=[[0x49,0,1,0xC0,0,0,0]];
            sent.Clear();
            var mismatch=ObdJ1979LegacyScan.Run(source,Exchange,()=>{},TimeSpan.FromSeconds(10),"1FA6P8TH0L5100000");
            Check(mismatch.VinMatch=="MISMATCH"&&!mismatch.Completed&&
                sent.SequenceEqual(new[]{"0100","0900","0901","0902"}),"Expected VIN stops before diagnostic values");
            Check(complete.Steps.Single(x => x.RequestHex == "0A").Status == "NOT_TESTED", "silence not zero");
            Check(complete.Steps.Single(x => x.RequestHex == "0902").Responses.All(x => x.Source == "10"), "VIN evidence attributed");
            sent.Clear();
            replies["0902"] = vinRows.Reverse().ToArray();
            var badVin = Run();
            Check(badVin.ObservedVin is null && badVin.Steps.Single(x => x.RequestHex == "0902").Status == "FAILED", "unordered VIN rejected");
            replies["0902"] = vinRows;
            sent.Clear(); packetSource = 0x11;
            var badSource = Run();
            Check(!badSource.TransportObserved && !badSource.Completed && sent.SequenceEqual(new[] {"0100"}), "no other source fallback");
            packetSource = source; sent.Clear();
            replies["03"]=[[0x7F,3,0x78]];
            var pending=Run();
            Check(pending.Steps.Single(x=>x.RequestHex=="03").Status=="FAILED",
                "unfinished pending response is not terminal NRC");
            replies["03"]=[[0x43,1,0x23,0,0,0,0]];
            sent.Clear(); failAt = "03";
            var lost = Run();
            Check(lost.StopReason == "TRANSPORT_ERROR" && lost.ObservedVin == vin && sent.Last() == "03", "partial evidence on driver fault");
            failAt = null; sent.Clear(); cancel = true;
            var cancelled = Run();
            Check(cancelled.StopReason == "CANCELLED" && sent.Count == 0, "cancelled before TX");

            string[] cli = [ObdJ1979LegacyCli.Command, "--adapter-index", "0", "--variant", "2", "--source", "10",
                "--confirm-legacy-profile", "--output", "new.json"];
            Check(ObdJ1979LegacyCli.Parse(cli).Variant == vpw, "independent CLI variant");
            Check(ObdJ1979LegacyCli.Parse([..cli,"--vin",vin]).ExpectedVin==vin,"CLI optional documented VIN");
            Reject(()=>ObdJ1979LegacyCli.Parse([..cli.Select(x=>x=="10"?"discover":x),"--vin",vin]),
                "VIN binding cannot be requested during discovery");
            var discoveryOptions = ObdJ1979LegacyCli.Parse(cli.Select(x => x == "10" ? "discover" : x).ToArray());
            Check(discoveryOptions.Source is null, "explicit unpinned discovery");
            var discovery = ObdJ1979LegacyDiscovery.Run((request, _) =>
            {
                Check(request.SequenceEqual(new byte[] {1,0}), "discovery only PID00");
                return new("686AF10100", [
                    new(0x10, "486B10410080000000", [0x41,0,0x80,0,0,0], 1),
                    new(0x11, "486B11410040000000", [0x41,0,0x40,0,0,0], 2),
                    new(0x11, "486B11410080000000", [0x41,0,0x80,0,0,0], 3),
                    new(0x12, "486B127F0111", [0x7F,1,0x11], 4)
                ]);
            }, () => {});
            Check(discovery.HasVerifiedSource && discovery.Sources.Single(x => x.ResponseSource == "10").Status == "PASSED",
                "verified source retained");
            Check(discovery.Sources.Single(x => x.ResponseSource == "11").Status == "FAILED" &&
                discovery.Sources.Single(x => x.ResponseSource == "12").Status == "NOT_TESTED",
                "conflicts and refusals do not become verified sources");
            Check(discovery.Sources.Single(x => x.ResponseSource == "11").Records.Count == 2, "conflict evidence retained");
            Reject(() => ObdJ1979LegacyCli.Parse([..cli, "--allow-obd-clear"]), "mixed command denied");
            Reject(() => ObdJ1979LegacyCli.Parse(cli.Where(x => x != "--confirm-legacy-profile").ToArray()), "explicit profile required");
            Reject(() => ObdJ1979LegacyCli.Parse([..cli, "--source", "11"]), "duplicate source denied");
        }
        return checks;
    }
}
