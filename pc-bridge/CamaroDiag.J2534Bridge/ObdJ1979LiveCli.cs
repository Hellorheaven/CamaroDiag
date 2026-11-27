using System.Globalization;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979LiveCli
{
    internal const string Command="--j1979-live";
    internal const string LegacyCommand="--j1979-legacy-live";
    internal sealed record Options(int AdapterIndex,int Profile,int Tx,int Rx,ObdJ1979LiveRecorder.Plan Plan,
        string Output,string? Csv,bool Quiet,ObdLegacyJ2534Transport.Variant? LegacyVariant=null,
        byte? LegacySource=null);
    internal sealed record Report(string Schema,string Protocol,string Version,string Adapter,int Profile,
        string? TxCanId,string? RxCanId,DateTimeOffset StartedAtUtc,DateTimeOffset FinishedAtUtc,
        ObdJ1979LiveRecorder.Result? Result,string? Error,string? LegacyVariant=null,
        string? SelectedResponseSource=null,string? InitializationEvidence=null,
        IReadOnlyList<ObdJ1979LegacyLiveLink.Trace>? TransportExchanges=null)
    {
        public bool Completed=>Error is null&&Result is {Completed:true};
        public bool WholeVehicleCoverageVerified=>false;
        public bool PhysicalVehicleVerified=>false;
        public bool PhysicallyAddressed=>LegacyVariant is null;
        public string Scope=>PhysicallyAddressed?"PHYSICAL_SELECTED_ECU_LIVE":"FUNCTIONAL_SELECTED_RESPONSE_LIVE";
        public string ResponseHexRepresentation=>PhysicallyAddressed?"APPLICATION_PAYLOAD":"NORMALIZED_APPLICATION_PAYLOAD";
    }
    internal static Options Parse(string[] args)
    {
        if(args.Length<2||!args[0].Equals(Command,StringComparison.OrdinalIgnoreCase)&&
            !args[0].Equals(LegacyCommand,StringComparison.OrdinalIgnoreCase))throw new ArgumentException("Expected live recorder command.");
        bool legacy=args[0].Equals(LegacyCommand,StringComparison.OrdinalIgnoreCase);
        var values=new Dictionary<string,string>(StringComparer.Ordinal);
        var pids=new List<byte>();bool confirm=false,quiet=false,functional=false,rawOnly=false;
        for(int i=1;i<args.Length;i++){
            string key=args[i];
            if(key==(legacy?"--confirm-legacy-profile":"--confirm-physical-channel")&&!confirm){confirm=true;continue;}
            if(legacy&&key=="--confirm-functional-scope"&&!functional){functional=true;continue;}
            if(key=="--quiet"&&!quiet){quiet=true;continue;}
            if(key=="--raw-only"&&!rawOnly){rawOnly=true;continue;}
            if(key is not ("--adapter-index" or "--protocol" or "--variant" or "--source" or "--tx" or "--rx" or "--vin" or "--pid" or
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
        int index=Number("--adapter-index");
        int profile=0,tx=0,rx=0;
        ObdLegacyJ2534Transport.Variant? variant=null;
        byte? source=null;
        if(legacy){
            if(!functional||values.ContainsKey("--protocol")||values.ContainsKey("--tx")||values.ContainsKey("--rx"))
                throw new ArgumentException("Explicit legacy functional scope required.");
            var number=Number("--variant");
            if(number is <1 or >5||!values.TryGetValue("--source",out var sourceText)||sourceText.Length!=2||
                !byte.TryParse(sourceText,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out var selected)||
                selected is 0 or 0xF1 or 0xFF)throw new ArgumentException("Documented legacy variant and response source required.");
            variant=(ObdLegacyJ2534Transport.Variant)number;source=selected;profile=number;
        }else{
            if(values.ContainsKey("--variant")||values.ContainsKey("--source"))throw new ArgumentException("Expected physical CAN channel.");
            profile=Number("--protocol");tx=Id("--tx");rx=Id("--rx");
        }
        int budget=Number("--budget-seconds",180);
        var output=values.GetValueOrDefault("--output");var csv=values.GetValueOrDefault("--csv");
        if(!confirm||index<0||!legacy&&!StandardObdPhysicalPolicy.SupportsChannel(tx,rx,profile)||
            budget is <1 or >600||string.IsNullOrWhiteSpace(output)||
            csv is not null&&(string.IsNullOrWhiteSpace(csv)||Path.GetFullPath(output).Equals(Path.GetFullPath(csv),StringComparison.OrdinalIgnoreCase)))
            throw new ArgumentException("Explicit physical channel, VIN, confirmations and distinct new files required.");
        var plan=new ObdJ1979LiveRecorder.Plan(pids.ToArray(),values.GetValueOrDefault("--vin")??"",
            Number("--cycles",30),Number("--interval-ms",1000),budget*1000,rawOnly).Validate();
        if(legacy&&!plan.RawOnly&&plan.Pids.Any(pid=>!ObdJ1979LiveRecorder.LegacyNumericPid(pid)))
            throw new ArgumentException("Selected PID exceeds legacy application width.");
        return new(index,profile,tx,rx,plan,output,csv,quiet,variant,source);
    }
    internal static Report Execute(J2534Adapter adapter,Options options,Action requireActive,
        Action<ObdJ1979LiveRecorder.Observation>? onSample=null)
    {
        var started=DateTimeOffset.UtcNow;
        ObdJ1979LiveRecorder.Result? result=null;
        string? error=null,initialization=null;
        ObdJ1979LegacyLiveLink? legacyLink=null;
        try{
            requireActive();
            if(options.LegacyVariant is {} variant&&options.LegacySource is byte source){
                if(!options.Plan.RawOnly&&options.Plan.Pids.Any(pid=>!ObdJ1979LiveRecorder.LegacyNumericPid(pid)))
                    throw new ArgumentException("Selected PID exceeds legacy application width.");
                using var session=ObdLegacyJ2534Transport.Open(adapter,variant,source,requireActive);
                initialization=session.InitEvidence;
                legacyLink=new ObdJ1979LegacyLiveLink(source,session.Read,requireActive,
                    rawPlan:options.Plan.RawOnly?options.Plan:null);
                result=ObdJ1979LiveRecorder.Run(legacyLink.Query,options.Plan,requireActive,onSample,
                    requestTimeout:req=>req[0]==9?20000:4000,legacy:true);
            }else{
                using var device=new J2534Device(adapter);
                device.Open(options.Tx,options.Rx,ObdJ1979CanNetwork.Extended(options.Profile),options.Profile);
                requireActive();
                result=ObdJ1979LiveRecorder.Run(device.Exchange,options.Plan,requireActive,onSample);
            }
        }catch(OperationCanceledException){error="CANCELLED";}
        catch(Exception e) when(e is IOException or InvalidDataException or ArgumentException or InvalidOperationException or
            TimeoutException or DllNotFoundException or EntryPointNotFoundException or BadImageFormatException or
            System.Runtime.InteropServices.SEHException){error=e.GetType().Name;}
        if(error is not null&&result is {Completed:true})result=result with{Completed=false,StopReason=error};
        return new(options.LegacyVariant.HasValue?"camarodiag-j1979-legacy-live-v1":"camarodiag-j1979-live-v1",
            "SAE-J1979",typeof(ObdJ1979LiveCli).Assembly.GetName().Version?.ToString()??"",
            adapter.DisplayName,options.Profile,options.LegacyVariant.HasValue?null:options.Tx.ToString("X"),
            options.LegacyVariant.HasValue?null:options.Rx.ToString("X"),started,DateTimeOffset.UtcNow,result,error,
            options.LegacyVariant?.ToString(),options.LegacySource?.ToString("X2"),initialization,legacyLink?.Traces);
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
                        (row.MultiSignals is { } signals?ObdJ1979MultiSignal.Format(signals):
                             row.Measurement is {} v?ObdJ1979PidValues.Format(v):options.Plan.RawOnly?row.ResponseHex??row.Status:row.Status));});
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
