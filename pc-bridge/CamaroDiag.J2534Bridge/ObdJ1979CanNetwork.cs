using System.Buffers.Binary;
using System.Diagnostics;

namespace CamaroDiag.J2534Bridge;

/// <summary>Classic CAN emissions discovery followed by explicitly selected,
/// separately opened ISO15765 physical channels. No implicit ECU selection.
/// </summary>
internal static class ObdJ1979CanNetwork
{
    internal sealed record Pair(int RequestId,int ResponseId);
    internal sealed record FrameEvidence(string CanId,string DataHex);
    internal sealed record Responder(string TxCanId,string RxCanId,string Status,string? Detail,
        IReadOnlyList<byte>? SupportedPids,bool? NextPage,byte? NegativeResponseCode,
        IReadOnlyList<FrameEvidence> Frames);
    internal sealed record Discovery(bool Extended,bool RequestSent,IReadOnlyList<Responder> Responders,
        IReadOnlyList<FrameEvidence> RawFrames,int IgnoredFrames,bool Completed,string? StopReason,long DurationMs)
    {
        public IReadOnlyList<string> EligibleResponseIds=>Completed?
            Responders.Where(r=>r.Status=="PASSED").Select(r=>r.RxCanId).ToArray():[];
        public bool PhysicalVehicleVerified=>false;
        public bool WholeVehicleCoverageVerified=>false;
    }
    internal sealed record EcuResult(string TxCanId,string RxCanId,string Status,
        ObdJ1979FunctionalScan.Result? Report,string? Error);
    internal sealed record NetworkResult(Discovery Discovery,IReadOnlyList<string> SelectedResponseIds,
        string? DeclaredVin,IReadOnlyList<EcuResult> EcuReports,bool Completed,string? StopReason,long DurationMs)
    {
        public bool PhysicalVehicleVerified=>false;
        public bool WholeVehicleCoverageVerified=>false;
        public string[] NotExecuted=>["MODE_04_CLEAR","MODE_08_CONTROL"];
    }
    internal interface IPhysicalSession:IDisposable
    {
        byte[] Exchange(byte[] payload,int timeoutMs);
    }
    private sealed class PhysicalSession:IPhysicalSession
    {
        private readonly J2534Device device;
        internal PhysicalSession(J2534Adapter adapter,Pair pair,int profile)
        {
            device=new J2534Device(adapter);
            try {device.Open(pair.RequestId,pair.ResponseId,Extended(profile),profile);}
            catch {device.Dispose();throw;}
        }
        public byte[] Exchange(byte[] payload,int timeoutMs)
        {
            if(!StandardObdPhysicalPolicy.AllowsRead(payload))throw new InvalidOperationException("Read-only campaign.");
            return device.Exchange(payload,timeoutMs);
        }
        public void Dispose()=>device.Dispose();
    }

    internal static bool Extended(int profile)=>profile switch {
        33 or 35=>false,34 or 36=>true,_=>throw new ArgumentException("Expected classic OBD profile 33-36.")
    };
    internal static uint Baud(int profile)=>profile switch {
        33 or 34=>500000,35 or 36=>250000,_=>throw new ArgumentException("Expected classic OBD profile 33-36.")
    };
    internal static Pair? AddressPair(uint rx,bool extended)
    {
        if(!extended)return rx is >=0x7E8 and <=0x7EF?new((int)rx-8,(int)rx):null;
        if((rx&0xFFFFFF00)!=0x18DAF100||(rx&255)>=0xF0)return null;
        return new((int)(0x18DA00F1|((rx&255)<<8)),(int)rx);
    }
    internal static uint[] ValidateTargets(IEnumerable<uint> selected,bool extended)
    {
        var values=selected.Take(33).ToArray();
        if(values.Length>32||values.Distinct().Count()!=values.Length||
            values.Any(x=>AddressPair(x,extended) is null))
            throw new ArgumentException("Invalid or duplicate selected response IDs.");
        return values;
    }
    internal static IsoTpCanFdSession.CanFrame Request(bool extended)=>
        new(extended?0x18DB33F1u:0x7DFu,false,[2,1,0,0xCC,0xCC,0xCC,0xCC,0xCC]);
    private static string Hex(uint id)=>id.ToString("X");
    private static FrameEvidence Evidence(IsoTpCanFdSession.CanFrame frame)=>
        new(Hex(frame.Id),Convert.ToHexString(frame.Data));
    private static Responder? Decode(IsoTpCanFdSession.CanFrame frame,bool extended)
    {
        if(frame.IsFd||AddressPair(frame.Id,extended) is not {} pair||frame.Data is null)return null;
        var data=frame.Data;
        var evidence=Evidence(frame);
        var invalid=new Responder(Hex((uint)pair.RequestId),Hex(frame.Id),"FAILED","INVALID_PID00_FRAME",
            null,null,null,[evidence]);
        if(data.Length!=8)return invalid;
        if(data[0]==6&&data[1]==0x41&&data[2]==0){
            var page=ObdJ1979PidSupport.Decode([1,0],data.AsSpan(1,6));
            return invalid with {Status="PASSED",Detail=null,SupportedPids=page.SupportedPids,NextPage=page.HasNextPage};
        }
        if(data[0]==3&&data[1]==0x7F&&data[2]==1&&data[3]!=0){
            return invalid with {Status="NOT_TESTED",
                Detail=data[3]==0x78?"PENDING_WITHOUT_FINAL_RESPONSE":$"NRC_{data[3]:X2}",
                NegativeResponseCode=data[3]==0x78?null:data[3]};
        }
        return invalid;
    }
    private static bool SameObservation(Responder a,Responder b)=>
        a.Status==b.Status&&a.Detail==b.Detail&&a.NextPage==b.NextPage&&
        a.NegativeResponseCode==b.NegativeResponseCode&&
        (a.SupportedPids is null?b.SupportedPids is null:
            b.SupportedPids is not null&&a.SupportedPids.SequenceEqual(b.SupportedPids));

    internal static async Task<Discovery> DiscoverAsync(IsoTpCanFdSession.ICanLink link,bool extended,
        TimeSpan timeout,CancellationToken token,Action requireActive)
    {
        if(timeout<=TimeSpan.Zero||timeout>TimeSpan.FromSeconds(30))throw new ArgumentOutOfRangeException(nameof(timeout));
        var watch=Stopwatch.StartNew();
        var rows=new SortedDictionary<uint,List<Responder>>();
        var frames=new List<FrameEvidence>();
        int ignored=0;
        string? stop=null;
        bool sent=false;
        try {
            token.ThrowIfCancellationRequested();requireActive();
            await link.SendAsync(Request(extended),token);
            sent=true;
            for(int count=0;count<512;count++){
                token.ThrowIfCancellationRequested();requireActive();
                var remaining=timeout-watch.Elapsed;
                if(remaining<=TimeSpan.Zero)break;
                IsoTpCanFdSession.CanFrame frame;
                try {frame=await link.ReceiveAsync(remaining,token);}
                catch(TimeoutException){break;}
                token.ThrowIfCancellationRequested();requireActive();
                if(watch.Elapsed>=timeout){ignored++;break;}
                var decoded=Decode(frame,extended);
                if(decoded is null)ignored++;
                else {
                    if(!rows.TryGetValue(frame.Id,out var group))rows.Add(frame.Id,group=[]);
                    group.Add(decoded);
                    frames.Add(decoded.Frames.Single());
                }
                if(count==511)stop="FRAME_LIMIT";
            }
        }catch(OperationCanceledException){stop="CANCELLED";}
        catch(Exception e) when(e is IOException or InvalidDataException or InvalidOperationException or ArgumentException){
            stop=e.GetType().Name;
        }
        var responders=rows.Values.Select(group=>{
            var terminals=group.Where(x=>x.Detail!="PENDING_WITHOUT_FINAL_RESPONSE").ToArray();
            var first=terminals.FirstOrDefault()??group[0];
            var evidence=group.SelectMany(x=>x.Frames).ToArray();
            if(terminals.Any(x=>!SameObservation(first,x)))
                return first with {Status="FAILED",Detail="CONFLICTING_RESPONSES",
                    SupportedPids=null,NextPage=null,NegativeResponseCode=null,Frames=evidence};
            return first with {Frames=evidence};
        }).ToArray();
        if(stop is null&&!responders.Any(x=>x.Status=="PASSED"))stop="NO_VERIFIED_RESPONDER";
        return new(extended,sent,responders,frames.ToArray(),ignored,stop is null,stop,watch.ElapsedMilliseconds);
    }

    internal static async Task<Discovery> DiscoverVendorAsync(J2534Adapter adapter,int profile,
        TimeSpan timeout,CancellationToken token,Action requireActive)
    {
        requireActive();token.ThrowIfCancellationRequested();
        bool extended=Extended(profile);
        uint flags=extended?0x100u:0;
        using var channel=J2534StandardChannel.Connect(adapter,J2534StandardChannel.Can,Baud(profile),flags);
        var mask=new byte[4];var pattern=new byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(mask,extended?0x1FFFFF00u:0x7F8u);
        BinaryPrimitives.WriteUInt32BigEndian(pattern,extended?0x18DAF100u:0x7E8u);
        channel.ConfigureFilter(mask,pattern,txFlags:flags);
        channel.ClearReceiveQueue();
        requireActive();
        return await DiscoverAsync(new IsoTpJ2534RawCanLink(channel,false,extended,false),extended,
            timeout,token,requireActive);
    }

    internal static NetworkResult Scan(Discovery discovery,IEnumerable<uint> selected,
        Func<Pair,IPhysicalSession> open,string? expectedVin,TimeSpan budget,Action requireActive)
    {
        if(budget<=TimeSpan.Zero||budget>TimeSpan.FromMinutes(10)||
            expectedVin is not null&&!System.Text.RegularExpressions.Regex.IsMatch(expectedVin,@"\A[A-HJ-NPR-Z0-9]{17}\z"))
            throw new ArgumentException("Invalid campaign budget or VIN.");
        var targets=ValidateTargets(selected,discovery.Extended);
        if(targets.Length==0)throw new ArgumentException("Explicit response IDs required.");
        var watch=Stopwatch.StartNew();
        var reports=new List<EcuResult>();
        bool Eligible(uint rx){
            var pair=AddressPair(rx,discovery.Extended)!;
            var rows=discovery.Responders.Where(x=>x.RxCanId==Hex(rx)).ToArray();
            return discovery.Completed&&rows.Length==1&&rows[0].Status=="PASSED"&&
                rows[0].TxCanId==Hex((uint)pair.RequestId);
        }
        string? stop=targets.All(Eligible)?null:"SELECTED_RESPONDER_UNVERIFIED";
        foreach(var rx in targets){
            var pair=AddressPair(rx,discovery.Extended)!;
            var txHex=Hex((uint)pair.RequestId);var rxHex=Hex(rx);
            if(stop is not null){reports.Add(new(txHex,rxHex,"NOT_TESTED",null,stop));continue;}
            ObdJ1979FunctionalScan.Result? result=null;
            string? error=null;
            try{
                requireActive();
                if(budget-watch.Elapsed<TimeSpan.FromMilliseconds(100)){
                    stop="BUDGET_EXHAUSTED";reports.Add(new(txHex,rxHex,"NOT_TESTED",null,stop));continue;
                }
                using(var session=open(pair)){
                    requireActive();
                    var left=budget-watch.Elapsed;
                    if(left<TimeSpan.FromMilliseconds(100)){
                        stop="BUDGET_EXHAUSTED";reports.Add(new(txHex,rxHex,"NOT_TESTED",null,stop));continue;
                    }
                    result=ObdJ1979FunctionalScan.Run(session.Exchange,requireActive,left,
                        retainCancelledEvidence:true,expectedVin:expectedVin);
                }
                if(!result.Completed)stop=result.StopReason??"INCOMPLETE_CAMPAIGN";
                error=stop;
            }catch(OperationCanceledException){stop=error="CANCELLED";}
            catch(Exception ex) when(ex is IOException or InvalidDataException or ArgumentException or
                InvalidOperationException or TimeoutException or DllNotFoundException or BadImageFormatException or
                EntryPointNotFoundException or System.Runtime.InteropServices.SEHException){
                stop=error=ex.GetType().Name;
            }
            if(error is not null&&result is {Completed:true})result=result with {Completed=false,StopReason=error};
            reports.Add(new(txHex,rxHex,error is null&&result is {Completed:true}?"PASSED":
                result is null&&error!="CANCELLED"?"FAILED":"NOT_TESTED",result,error));
        }
        return new(discovery,targets.Select(Hex).ToArray(),expectedVin,reports.ToArray(),
            stop is null&&reports.All(x=>x.Status=="PASSED"),stop,watch.ElapsedMilliseconds);
    }
    internal static NetworkResult ScanVendor(J2534Adapter adapter,int profile,Discovery discovery,
        IEnumerable<uint> selected,string? vin,TimeSpan budget,Action requireActive)
    {
        if(Extended(profile)!=discovery.Extended)throw new ArgumentException("Discovery addressing differs from physical profile.");
        return Scan(discovery,selected,pair=>new PhysicalSession(adapter,pair,profile),vin,budget,requireActive);
    }
}
