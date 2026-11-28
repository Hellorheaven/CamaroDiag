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
        now=0;reads=0;
        var channels=ObdJ1979LiveRecorder.Run((req,_)=>Convert.ToHexString(req) switch {
            "0100"=>[0x41,0,0,0,0,1],
            "0120"=>[0x41,0x20,0,0,0,1],
            "0140"=>[0x41,0x40,0,0,0,1],
            "0160"=>[0x41,0x60,2,0,0,0],
            "0167"=>++reads==1?[0x41,0x67,3,90,100]:[0x41,0x67,1,94,0],
            _=>Normal(req)
        },plan with{Pids=[0x67],Cycles=2},()=>{},clock:()=>now,delay:ms=>now+=ms);
        Check(channels.Completed&&channels.MultiSignalSummary.Count==2,"multi-channel completion");
        Check(channels.MultiSignalSummary[0].Count==2&&channels.MultiSignalSummary[0].Mean==52,
            "independent first channel mean");
        Check(channels.MultiSignalSummary[1].Count==1&&channels.MultiSignalSummary[1].Missing==1&&
            channels.MultiSignalSummary[1].Mean==60,"missing second channel excluded");
        Check(channels.Observations.All(x=>x.Measurement is null),"no scalar projection of multi-channel PID");
        Check(ObdJ1979LiveRecorder.Csv(channels).First().EndsWith("signal_name,raw_encoded"),
            "multi-channel CSV header");
        now=0;
        var diesel=ObdJ1979LiveRecorder.Run((req,_)=>Convert.ToHexString(req) switch {
            "0100"=>[0x41,0,0,0,0,1],
            "0120"=>[0x41,0x20,0,0,0,1],
            "0140"=>[0x41,0x40,0,0,0,1],
            "0160"=>[0x41,0x60,0,0,0,1],
            "0180"=>[0x41,0x80,0x80,0,0,0],
            "0181"=>[0x41,0x81,0x1F,..Enumerable.Repeat((byte)0xFF,40)],
            _=>Normal(req)
        },plan with{Pids=[0x81],Cycles=2},()=>{},clock:()=>now,delay:ms=>now+=ms);
        Check(diesel.Completed&&diesel.MultiSignalSummary.Count==10,"AECD timers isolated");
        Check(diesel.MultiSignalSummary.All(x=>x.Count==2&&x.Mean==4294967295d),
            "AECD unsigned 32-bit maximum preserved");
        Check(diesel.Observations.All(x=>x.Measurement is null),"AECD does not masquerade as scalar");
        Check(ObdJ1979LiveRecorder.Csv(diesel).Count()==21,"all AECD channels in CSV");
        now=0;sent.Clear();
        byte[] Configured(byte[] request)=>Convert.ToHexString(request) switch {
            "0100"=>Convert.FromHexString("410000210001"),
            "0120"=>Convert.FromHexString("412000000001"),
            "0140"=>Convert.FromHexString("414000030000"),
            "014F"=>Convert.FromHexString("414F0410004D"),
            "0150"=>Convert.FromHexString("415064000000"),
            "010B"=>Convert.FromHexString("410BFF"),
            "0110"=>Convert.FromHexString("4110FFFF"),
            _=>Normal(request)
        };
        var configured=ObdJ1979LiveRecorder.Run((req,_)=>{
            sent.Add(Convert.ToHexString(req));return Configured(req);
        },plan with{Pids=[0x0B,0x10],Cycles=1},()=>{},clock:()=>now,delay:ms=>now+=ms);
        Check(configured.Completed,"configured MAP and MAF acquisition");
        Check(Math.Abs(configured.Summary[0].Mean!.Value-770d)<1e-8 &&
              Math.Abs(configured.Summary[1].Mean!.Value-1000d)<1e-8,"ECU configured scales");
        Check(sent.IndexOf("014F")<sent.IndexOf("010B")&&sent.IndexOf("0150")<sent.IndexOf("0110"),
            "configuration precedes acquisition");
        now=0;sent.Clear();
        var noConfig=ObdJ1979LiveRecorder.Run((req,_)=>{
            sent.Add(Convert.ToHexString(req));
            if(req.SequenceEqual(new byte[]{1,0x50}))throw new TimeoutException();
            return Configured(req);
        },plan with{Pids=[0x10],Cycles=1},()=>{},clock:()=>now,delay:ms=>now+=ms);
        Check(!noConfig.Completed&&noConfig.StopReason=="SCALING_UNVERIFIED"&&!sent.Contains("0110"),
            "missing advertised configuration blocks acquisition");
        var customCurrent=ObdJ1979ScalingContext.Context.Build([
            new("0100","410000210001","PASSED"),new("0120","412010001001","PASSED"),
            new("0140","414010030000","PASSED"),new("014F","414F0400404D","RAW")]);
        foreach(var (encoded,expected) in new[]{("0000",0d),("7FFF",63.998046875d),
            ("8000",-64d),("9C40",-49.875d),("FFFF",-0.001953125d)}){
            var current=customCurrent.Decode([1,0x34],Convert.FromHexString("41347D00"+encoded))!.Signals[1];
            Check(current.Number is double number&&Math.Abs(number-expected)<1e-9&&
                current.ScaleSource=="ECU_CONFIGURATION","Custom current uses signed binary encoding");
        }
        now=0;reads=0;sent.Clear();
        var trim=ObdJ1979LiveRecorder.Run((req,_)=>{
            var hex=Convert.ToHexString(req);sent.Add(hex);now+=10;
            return hex switch {
                "0100"=>Convert.FromHexString("410004000008"),
                "011D"=>Convert.FromHexString("411D30"),
                "0106"=>Convert.FromHexString(++reads==1?"41068090":"410690"),
                _=>Normal(req)
            };
        },plan with{Pids=[6],Cycles=2},()=>{},clock:()=>now,delay:ms=>now+=ms);
        Check(trim.Completed&&trim.MultiSignalSummary.Count==2,"two-bank trim recording");
        Check(sent.SequenceEqual(new[]{"0902","0100","011D","0106","0106","0902"}),"layout admission before acquisition");
        Check(trim.MultiSignalSummary[0].Mean==6.25&&trim.MultiSignalSummary[1].Mean==12.5&&
            trim.MultiSignalSummary[1].Missing==1,"independent bank statistics");
        foreach(int pid in new[]{6,7,8,9,0x55,0x56,0x57,0x58}){
            var decoded=ObdJ1979OperatingContext.DecodeTrim([1,(byte)pid],[0,255],255)!;
            Check(decoded.Signals[0].Number==-100&&decoded.Signals[1].Number==99.21875,
                "trim endpoint scaling");
            Check(ObdJ1979OperatingContext.DecodeTrim([1,(byte)pid],[128,144],null)!
                .Signals[1].Number is null,"unverified upper bank is not a value");
        }
        var cycle=ObdJ1979OperatingContext.DecodeCycle([0,17,1,1],[128,7,1,0])!;
        Check(cycle.Consistent&&cycle.SupportVerified&&cycle.Monitors[0].Enabled==true&&
            cycle.Monitors[0].Complete==false&&cycle.Monitors[1].Enabled==false&&
            cycle.Monitors[1].Complete==true,"cycle support, enable and completion are independent");
        Check(ObdJ1979OperatingContext.DecodeCycle([0,1,1,0],null)!.Monitors.All(m=>m.Complete is null),
            "missing baseline does not prove completion");
        Check(ObdJ1979OperatingContext.DecodeCycle([0,128,0,0],null) is null,"reserved cycle field rejected");
        return count;
    }
}
