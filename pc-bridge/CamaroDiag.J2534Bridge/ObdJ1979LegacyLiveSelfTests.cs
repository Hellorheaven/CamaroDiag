namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979LegacyLiveSelfTests
{
    private const string Vin="1G1FA1RXXR0123456";
    private static Dictionary<string,byte[][]> Replies(string vin=Vin){
        var raw=new byte[3].Concat(System.Text.Encoding.ASCII.GetBytes(vin)).ToArray();
        return new(){
            ["0100"]=[[0x41,0,0x08,0x10,0,0]],["0900"]=[[0x49,0,1,0xC0,0,0,0]],
            ["0901"]=[[0x49,1,5]],["0902"]=raw.Chunk(4).Select((x,i)=>new byte[]{0x49,2,(byte)(i+1)}.Concat(x).ToArray()).ToArray(),
            ["0105"]=[[0x41,5,0x5A]],["010C"]=[[0x41,0x0C,0x0F,0xA0]]
        };
    }
    internal static int Run(){
        int count=0;
        void Check(bool test,string message){if(!test)throw new InvalidOperationException("Legacy live: "+message);count++;}
        var plan=new ObdJ1979LiveRecorder.Plan([5,12],Vin,2,100,30000);
        foreach(var variant in Enum.GetValues<ObdLegacyJ2534Transport.Variant>()){
            long now=0;var replies=Replies();var sent=new List<string>();
            ObdLegacyJ2534Transport.Exchange Exchange(byte[] req,int timeout){
                var hex=Convert.ToHexString(req);sent.Add(hex);now+=10;
                return new(Convert.ToHexString(ObdLegacyJ2534Transport.FrameRead(variant,req)),
                    replies[hex].Select((r,i)=>new ObdLegacyJ2534Transport.Packet(0x10,
                        Convert.ToHexString(new byte[]{0x48,0x6B,0x10}.Concat(r).ToArray()),r,(uint)i)).ToArray());
            }
            var link=new ObdJ1979LegacyLiveLink(0x10,Exchange,()=>{},()=>now);
            var report=ObdJ1979LiveRecorder.Run(link.Query,plan,()=>{},clock:()=>now,delay:ms=>now+=ms,
                requestTimeout:r=>r[0]==9?20000:4000);
            Check(report.Completed&&report.ObservedVin==Vin&&report.CompletedCycles==2,"identity and live cycles");
            Check(sent.Take(5).SequenceEqual(new[]{"0100","0900","0901","0902","0100"}),"legacy VIN count before sampling");
            Check(link.Traces.Count(t=>t.RequestHex=="0902")==2&&link.Traces.First(t=>t.RequestHex=="0902").Frames.Count==5,
                "raw legacy frames separate from normalized payload");
            Check(report.Summary[0].Mean==50&&report.Summary[1].Mean==1000,"numeric formulas reused");
            Check(sent.All(x=>!x.StartsWith("04")&&!x.StartsWith("08")),"read only");
            replies["0902"]=replies["0902"].Skip(1).ToArray();sent.Clear();now=0;
            var incomplete=new ObdJ1979LegacyLiveLink(0x10,Exchange,()=>{},()=>now);
            var failed=ObdJ1979LiveRecorder.Run(incomplete.Query,plan,()=>{},clock:()=>now,delay:ms=>now+=ms);
            Check(!failed.Completed&&failed.Observations.Count==0&&incomplete.Traces[^1].Detail=="VIN_FRAGMENT_SEQUENCE_INVALID",
                "incomplete VIN does not qualify");
        }
        var wrong=new ObdJ1979LegacyLiveLink(0x10,(_,_)=>new("0100",
            [new(0x11,"486B11410080100000",new byte[]{0x41,0,0x80,0x10,0,0},0)]),()=>{});
        bool rejected=false;
        try{wrong.Query([9,2],1000);}catch(InvalidDataException){rejected=true;}
        Check(rejected&&wrong.Traces.Single().Detail=="RESPONSE_SOURCE_CHANGED","source change rejected");
        var args=new[]{"--j1979-legacy-live","--adapter-index","0","--variant","2","--source","10",
            "--vin",Vin,"--pid","05","--confirm-legacy-profile","--confirm-functional-scope","--output","new.json"};
        var parsed=ObdJ1979LiveCli.Parse(args);
        Check(parsed.LegacyVariant==ObdLegacyJ2534Transport.Variant.Vpw&&parsed.LegacySource==0x10,"legacy CLI");
        rejected=false;
        try{ObdJ1979LiveCli.Parse(args.Where(x=>x!="--confirm-functional-scope").ToArray());}catch(ArgumentException){rejected=true;}
        Check(rejected,"functional scope explicit");
        rejected=false;
        try{ObdJ1979LiveCli.Parse([..args,"--tx","7E0"]);}catch(ArgumentException){rejected=true;}
        Check(rejected,"CAN physical channel cannot be mixed with legacy");
        return count;
    }
}
