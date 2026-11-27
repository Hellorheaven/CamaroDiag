using System.Globalization;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979LiveCli
{
    internal const string Command="--j1979-live";
    internal sealed record Options(int AdapterIndex,int Profile,int Tx,int Rx,ObdJ1979LiveRecorder.Plan Plan,
        string Output,string? Csv,bool Quiet);
    internal sealed record Report(string Schema,string Protocol,string Version,string Adapter,int Profile,
        string TxCanId,string RxCanId,DateTimeOffset StartedAtUtc,DateTimeOffset FinishedAtUtc,
        ObdJ1979LiveRecorder.Result? Result,string? Error)
    {
        public bool Completed=>Error is null&&Result is {Completed:true};
        public bool WholeVehicleCoverageVerified=>false;
        public bool PhysicalVehicleVerified=>false;
    }
    internal static Options Parse(string[] args)
    {
        if(args.Length<2||!args[0].Equals(Command,StringComparison.OrdinalIgnoreCase))throw new ArgumentException("Expected live recorder command.");
        var values=new Dictionary<string,string>(StringComparer.Ordinal);
        var pids=new List<byte>();bool confirm=false,quiet=false;
        for(int i=1;i<args.Length;i++){
            string key=args[i];
            if(key=="--confirm-physical-channel"&&!confirm){confirm=true;continue;}
            if(key=="--quiet"&&!quiet){quiet=true;continue;}
            if(key is not ("--adapter-index" or "--protocol" or "--tx" or "--rx" or "--vin" or "--pid" or
                "--cycles" or "--interval-ms" or "--budget-seconds" or "--output" or "--csv")||
                i+1>=args.Length||args[i+1].StartsWith("--",StringComparison.Ordinal))throw new ArgumentException("Unexpected option.");
            string value=args[++i];
            if(key=="--pid"){
                if(value.Length!=2||!byte.TryParse(value,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out var pid)||pids.Count>=32)
                    throw new ArgumentException("Expected two-digit PID.");
                pids.Add(pid);
            }else if(!values.TryAdd(key,value))throw new ArgumentException("Duplicate option.");
        }
        int Number(string key,int? fallback=null){
            if(!values.TryGetValue(key,out var text)&&fallback.HasValue)return fallback.Value;
            if(!int.TryParse(text,NumberStyles.None,CultureInfo.InvariantCulture,out var n))throw new ArgumentException("Expected integer: "+key);
            return n;
        }
        int Id(string key){
            if(!values.TryGetValue(key,out var text)||text.Length is <1 or >8||
                !int.TryParse(text,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out int id)||id<0)
                throw new ArgumentException("Expected physical CAN ID.");return id;
        }
        int index=Number("--adapter-index"),profile=Number("--protocol"),tx=Id("--tx"),rx=Id("--rx");
        int budget=Number("--budget-seconds",180);
        var output=values.GetValueOrDefault("--output");var csv=values.GetValueOrDefault("--csv");
        if(!confirm||index<0||!StandardObdPhysicalPolicy.SupportsChannel(tx,rx,profile)||
            budget is <1 or >600||string.IsNullOrWhiteSpace(output)||
            csv is not null&&(string.IsNullOrWhiteSpace(csv)||Path.GetFullPath(output).Equals(Path.GetFullPath(csv),StringComparison.OrdinalIgnoreCase)))
            throw new ArgumentException("Explicit physical channel, VIN, confirmations and distinct new files required.");
        var plan=new ObdJ1979LiveRecorder.Plan(pids.ToArray(),values.GetValueOrDefault("--vin")??"",
            Number("--cycles",30),Number("--interval-ms",1000),budget*1000).Validate();
        return new(index,profile,tx,rx,plan,output,csv,quiet);
    }
    internal static Report Execute(J2534Adapter adapter,Options options,Action requireActive,
        Action<ObdJ1979LiveRecorder.Observation>? onSample=null)
    {
        var started=DateTimeOffset.UtcNow;
        ObdJ1979LiveRecorder.Result? result=null;
        string? error=null;
        try{
            requireActive();
            using var device=new J2534Device(adapter);
            device.Open(options.Tx,options.Rx,ObdJ1979CanNetwork.Extended(options.Profile),options.Profile);
            requireActive();
            result=ObdJ1979LiveRecorder.Run(device.Exchange,options.Plan,requireActive,onSample);
        }catch(OperationCanceledException){error="CANCELLED";}
        catch(Exception e) when(e is IOException or InvalidDataException or ArgumentException or InvalidOperationException or
            TimeoutException or DllNotFoundException or EntryPointNotFoundException or BadImageFormatException or
            System.Runtime.InteropServices.SEHException){error=e.GetType().Name;}
        if(error is not null&&result is {Completed:true})result=result with{Completed=false,StopReason=error};
        return new("camarodiag-j1979-live-v1","SAE-J1979",typeof(ObdJ1979LiveCli).Assembly.GetName().Version?.ToString()??"",
            adapter.DisplayName,options.Profile,options.Tx.ToString("X"),options.Rx.ToString("X"),started,DateTimeOffset.UtcNow,result,error);
    }
    private static string Encode(Report report)=>JsonSerializer.Serialize(report,new JsonSerializerOptions{
        WriteIndented=true,PropertyNamingPolicy=JsonNamingPolicy.CamelCase});
    private static void Write(FileStream stream,IEnumerable<string> rows){
        using var writer=new StreamWriter(stream,leaveOpen:true);
        foreach(var row in rows)writer.WriteLine(row);
        writer.Flush();stream.Flush(true);
    }
    internal static void WriteNew(string path,Report report,bool csv){
        if(csv&&report.Result is null)throw new InvalidDataException("No measurement report.");
        using var file=new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        Write(file,csv?ObdJ1979LiveRecorder.Csv(report.Result!):new[]{Encode(report)});
    }
    internal static int Run(string[] args){
        try{
            var options=Parse(args);
            var adapters=J2534Registry.Discover();
            if(options.AdapterIndex>=adapters.Count)throw new ArgumentException("Adapter index out of range.");
            using var output=new FileStream(options.Output,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            using var csv=options.Csv is null?null:new FileStream(options.Csv,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            using var cancel=new CancellationTokenSource();
            ConsoleCancelEventHandler handler=(_,e)=>{e.Cancel=true;cancel.Cancel();};
            Console.CancelKeyPress+=handler;
            try{
                var report=Execute(adapters[options.AdapterIndex],options,cancel.Token.ThrowIfCancellationRequested,
                    row=>{if(!options.Quiet)Console.WriteLine($"{row.ElapsedMs} ms {row.Pid:X2} "+
                        (row.Measurement is {} v?ObdJ1979PidValues.Format(v):row.Status));});
                Write(output,[Encode(report)]);
                if(csv is not null)Write(csv,report.Result is {} result?ObdJ1979LiveRecorder.Csv(result):
                    new[]{"cycle,pid_hex,started_elapsed_ms,elapsed_ms,duration_ms,status,value,unit,request_hex,response_hex,detail"});
                return report.Completed?0:7;
            }finally{Console.CancelKeyPress-=handler;}
        }catch(Exception e) when(e is ArgumentException or IOException or UnauthorizedAccessException){
            Console.Error.WriteLine("J1979 live: "+e.Message);return 6;
        }
    }
}
