namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979CanNetworkSelfTests
{
    private const string Vin="1G1FA1RXXR0123456";
    private sealed class Link(IEnumerable<IsoTpCanFdSession.CanFrame> frames):IsoTpCanFdSession.ICanLink
    {
        private readonly Queue<IsoTpCanFdSession.CanFrame> queue=new(frames);
        public List<IsoTpCanFdSession.CanFrame> Sent {get;}=[];
        public Task SendAsync(IsoTpCanFdSession.CanFrame frame,CancellationToken token){
            token.ThrowIfCancellationRequested();Sent.Add(frame);return Task.CompletedTask;
        }
        public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(TimeSpan timeout,CancellationToken token){
            token.ThrowIfCancellationRequested();
            if(queue.Count==0)throw new TimeoutException();return Task.FromResult(queue.Dequeue());
        }
        public Task WaitMinimumSeparationAsync(TimeSpan delay,CancellationToken token)=>Task.CompletedTask;
    }
    private sealed class Physical(string vin,string dtc):ObdJ1979CanNetwork.IPhysicalSession
    {
        public List<string> Sent {get;}=[];
        public byte[] Exchange(byte[] req,int timeout){
            var command=Convert.ToHexString(req);Sent.Add(command);
            return command switch {
                "0902"=>[0x49,2,1,..System.Text.Encoding.ASCII.GetBytes(vin)],
                "03"=>[0x43,1,..Convert.FromHexString(dtc)],
                "07"=>[0x47,0],"0A"=>[0x4A,0],"020200"=>[0x42,2,0,0,0],
                _=>[(byte)(req[0]+0x40),req[1],0,0,0,0]
            };
        }
        public void Dispose(){}
    }
    private static IsoTpCanFdSession.CanFrame Positive(uint id,byte first=0x80)=>
        new(id,false,[6,0x41,0,first,0,0,0,0xCC]);
    internal static int Run()
    {
        int count=0;
        void Check(bool test,string message){if(!test)throw new InvalidOperationException("J1979 network: "+message);count++;}
        ObdJ1979CanNetwork.Discovery Discover(Link link,bool extended=false)=>
            ObdJ1979CanNetwork.DiscoverAsync(link,extended,TimeSpan.FromSeconds(1),CancellationToken.None,()=>{}).GetAwaiter().GetResult();
        var pending=new IsoTpCanFdSession.CanFrame(0x7E8,false,[3,0x7F,1,0x78,0,0,0,0]);
        var link=new Link([pending,Positive(0x7E8),Positive(0x7E8),Positive(0x7E9),Positive(0x7E9,0x40),
            new(0x7EA,false,[3,0x7F,1,0x11,0,0,0,0]),Positive(0x123)]);
        var discovery=Discover(link);
        Check(link.Sent.Count==1&&link.Sent[0].Id==0x7DF,"single functional request");
        Check(discovery.EligibleResponseIds.SequenceEqual(new[]{"7E8"}),"conflicting response excluded");
        Check(discovery.Responders.Single(x=>x.RxCanId=="7E8").Frames.Count==3,"duplicate and pending evidence");
        Check(discovery.Responders.Single(x=>x.RxCanId=="7E9").Detail=="CONFLICTING_RESPONSES","conflict retained");
        Check(discovery.Responders.Single(x=>x.RxCanId=="7EA").NegativeResponseCode==0x11,"terminal NRC");
        Check(discovery.IgnoredFrames==1,"unrelated frame ignored");
        var ext=new Link([Positive(0x18DAF110),Positive(0x18DAF1F1),Positive(0x7E8)]);
        var d29=Discover(ext,true);
        Check(ext.Sent.Single().Id==0x18DB33F1&&d29.EligibleResponseIds.SequenceEqual(new[]{"18DAF110"}),"29-bit family");
        Check(ObdJ1979CanNetwork.AddressPair(0x18DAF110,true)?.RequestId==0x18DA10F1,"29-bit physical mapping");
        var capped=Discover(new Link(Enumerable.Repeat(Positive(0x7E8),512)));
        Check(!capped.Completed&&capped.EligibleResponseIds.Count==0,"bounded observation");
        var both=Discover(new Link([Positive(0x7E8),Positive(0x7E9),Positive(0x7EA)]));
        var opened=new List<int>();var sessions=new List<Physical>();
        var network=ObdJ1979CanNetwork.Scan(both,[0x7E8,0x7E9],pair=>{
            opened.Add(pair.ResponseId);var p=new Physical(Vin,pair.ResponseId==0x7E8?"0123":"0456");sessions.Add(p);return p;
        },Vin,TimeSpan.FromSeconds(20),()=>{});
        Check(network.Completed&&opened.SequenceEqual(new[]{0x7E8,0x7E9}),"explicit selected physical scans only");
        Check(network.EcuReports[0].Report!.DtcModes[0].Codes!.SequenceEqual(new[]{"P0123"})&&
            network.EcuReports[1].Report!.DtcModes[0].Codes!.SequenceEqual(new[]{"P0456"}),"DTC separation");
        Check(!network.WholeVehicleCoverageVerified&&!network.PhysicalVehicleVerified,"no vehicle claim");
        Check(sessions.All(x=>!x.Sent.Contains("04")&&!x.Sent.Contains("0801")),"no mutations");
        var absent=ObdJ1979CanNetwork.Scan(both,[0x7E8,0x7EF],_=>throw new Exception("unexpected open"),
            null,TimeSpan.FromSeconds(20),()=>{});
        Check(absent.StopReason=="SELECTED_RESPONDER_UNVERIFIED"&&absent.EcuReports.All(x=>x.Report is null),"all selected targets verified before open");
        opened.Clear();
        var mismatch=ObdJ1979CanNetwork.Scan(both,[0x7E8,0x7E9],pair=>{
            opened.Add(pair.ResponseId);return new Physical("1FA6P8TH0L5100000","0123");
        },Vin,TimeSpan.FromSeconds(20),()=>{});
        Check(mismatch.StopReason=="VIN_MISMATCH"&&opened.SequenceEqual(new[]{0x7E8}),"mismatch stops later targets");
        var args=new[]{"--j1979-network","--adapter-index","0","--protocol","33","--source","7E8","--source","7E9",
            "--confirm-functional-pid00","--confirm-physical-channels","--output","new.json"};
        Check(ObdJ1979CanNetworkCli.Parse(args).Sources.SequenceEqual(new uint[]{0x7E8,0x7E9}),"CLI multiple sources");
        bool rejected=false;
        try{ObdJ1979CanNetworkCli.Parse([..args,"--source","7E8"]);}catch(ArgumentException){rejected=true;}
        Check(rejected,"duplicate source rejected");
        rejected=false;
        try{ObdJ1979CanNetworkCli.Parse(args.Where(x=>x!="--confirm-physical-channels").ToArray());}
        catch(ArgumentException){rejected=true;}
        Check(rejected,"physical confirmation required");
        return count;
    }
}
