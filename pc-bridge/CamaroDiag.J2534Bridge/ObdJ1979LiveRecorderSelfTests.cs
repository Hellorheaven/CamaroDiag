namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979LiveRecorderSelfTests
{
    private const string Vin="1G1FA1RXXR0123456";
    private static byte[] Normal(byte[] request)=>Convert.ToHexString(request) switch{
        "0902"=>[0x49,2,1,..System.Text.Encoding.ASCII.GetBytes(Vin)],
        "0100"=>[0x41,0,0x08,0x10,0,0],"0105"=>[0x41,5,0x5A],"010C"=>[0x41,0x0C,0x0F,0xA0],
        _=>throw new InvalidOperationException("Unexpected test query.")
    };
    internal static int Run(){
        int count=0;
        void Check(bool value,string message){if(!value)throw new InvalidOperationException("J1979 live: "+message);count++;}
        var plan=new ObdJ1979LiveRecorder.Plan([5,12],Vin,3,500,30000);
        long now=0;
        var sent=new List<string>();var published=new List<ObdJ1979LiveRecorder.Observation>();
        var report=ObdJ1979LiveRecorder.Run((req,_)=>{
            sent.Add(Convert.ToHexString(req));now+=50;return Normal(req);
        },plan,()=>{},published.Add,()=>now,ms=>now+=ms);
        Check(report.Completed&&report.CompletedCycles==3&&published.Count==6,"complete sample cycles");
        Check(sent.Take(2).SequenceEqual(new[]{"0902","0100"})&&sent[^1]=="0902","initial and final identity");
        Check(published[2].StartedElapsedMs-published[0].StartedElapsedMs>=500,"minimum cycle interval");
        Check(report.Summary[0].Mean==50&&report.Summary[1].Minimum==1000,"verified statistics");
        Check(!report.PhysicalVehicleVerified&&!report.WholeVehicleCoverageVerified,"no physical claim");
        sent.Clear();now=0;
        var absent=ObdJ1979LiveRecorder.Run((req,_)=>{
            sent.Add(Convert.ToHexString(req));return Normal(req);
        },plan with{Pids=[5,13]},()=>{},clock:()=>now,delay:ms=>now+=ms);
        Check(absent.StopReason=="SELECTED_PID_NOT_VERIFIED"&&sent.SequenceEqual(new[]{"0902","0100"}),"no unverified PID query");
        int reads=0;now=0;
        var gaps=ObdJ1979LiveRecorder.Run((req,_)=>{
            if(req.SequenceEqual(new byte[]{1,5})){
                reads++;
                if(reads==2)throw new TimeoutException();
                if(reads==3)return [0x7F,1,0x11];
            }
            return Normal(req);
        },plan,()=>{},clock:()=>now,delay:ms=>now+=ms);
        var rows=gaps.Observations.Where(x=>x.Pid==5).ToArray();
        Check(rows.Select(x=>x.Status).SequenceEqual(new[]{"PASSED","TIMEOUT","NEGATIVE"})&&
            rows[1].Measurement is null&&rows[2].Measurement is null,"missing readings not reused");
        Check(gaps.Summary[0].Mean==50&&gaps.Summary[0].StatusCounts["PASSED"]==1,"statistics exclude gaps");
        Check(ObdJ1979LiveRecorder.Csv(gaps).Count()==7,"CSV includes every observation");
        now=0;sent.Clear();
        var late=ObdJ1979LiveRecorder.Run((req,timeout)=>{
            sent.Add(Convert.ToHexString(req));if(req[0]==1&&req[1]==5)now+=timeout;return Normal(req);
        },plan with{BudgetMs=100},()=>{},clock:()=>now,delay:ms=>now+=ms);
        Check(late.StopReason=="BUDGET_EXHAUSTED"&&late.Observations.Single().Detail=="LATE_RESPONSE"&&
            late.Observations.Single().Measurement is null,"late response retained raw only");
        now=0;
        var interrupted=ObdJ1979LiveRecorder.Run((req,_)=>{
            if(req.SequenceEqual(new byte[]{1,12}))throw new OperationCanceledException();return Normal(req);
        },plan,()=>{},clock:()=>now,delay:ms=>now+=ms);
        Check(interrupted.Cancelled&&interrupted.Observations.Count==2&&interrupted.CompletedCycles==0,"partial cancelled cycle");
        now=0;int identity=0;
        var mismatch=ObdJ1979LiveRecorder.Run((req,_)=>{
            if(req[0]==9&&++identity>1)return [0x49,2,1,..System.Text.Encoding.ASCII.GetBytes("1FA6P8TH0L5100000")];
            return Normal(req);
        },plan with{Cycles=1},()=>{},clock:()=>now,delay:ms=>now+=ms);
        Check(!mismatch.Completed&&mismatch.StopReason=="VIN_MISMATCH"&&mismatch.Observations.Count==2,"final identity mismatch");
        bool rejected=false;
        try{(plan with{Pids=[0x20]}).Validate();}catch(ArgumentException){rejected=true;}
        Check(rejected,"bitmap is not a numeric selection");
        var args=new[]{"--j1979-live","--adapter-index","0","--protocol","33","--tx","7E0","--rx","7E8",
            "--vin",Vin,"--pid","05","--pid","0C","--confirm-physical-channel","--output","new.json"};
        Check(ObdJ1979LiveCli.Parse(args).Plan.Pids.SequenceEqual(new byte[]{5,12}),"CLI selection");
        rejected=false;
        try{ObdJ1979LiveCli.Parse([..args,"--pid","05"]);}catch(ArgumentException){rejected=true;}
        Check(rejected,"duplicate PID rejected");
        rejected=false;
        try{ObdJ1979LiveCli.Parse(args.Where(x=>x!="--confirm-physical-channel").ToArray());}catch(ArgumentException){rejected=true;}
        Check(rejected,"physical confirmation required");
        return count;
    }
}
