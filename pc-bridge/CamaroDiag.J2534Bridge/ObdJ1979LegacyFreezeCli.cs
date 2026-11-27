using System.Globalization;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>Explicit functional legacy Mode 02. The source identifies the
/// selected receiver, not the broadcast destination. Driver-managed
/// checksums are not independently verified by this report.</summary>
internal static class ObdJ1979LegacyFreezeCli
{
    internal const string Command="--j1979-legacy-freeze";
    internal sealed record Options(int AdapterIndex,ObdLegacyJ2534Transport.Variant Variant,byte Source,
        ObdJ1979FreezeCampaign.Plan Plan,string Output);
    internal sealed record Report(string Schema,string Protocol,string Transport,string Version,
        string Adapter,int ExpectedProtocol,string SelectedResponseSource,uint J2534ProtocolId,uint Baud,
        string? InitializationEvidence,DateTimeOffset StartedAtUtc,DateTimeOffset FinishedAtUtc,
        string? TransportProblem,ObdJ1979FreezeCampaign.Result Result,
        IReadOnlyList<ObdJ1979LegacyLiveLink.Trace> TransportExchanges)
    {
        public string Scope=>"FUNCTIONAL_SELECTED_RESPONSE_FREEZE";
        public bool PhysicallyAddressed=>false;
        public bool FunctionalScopeConfirmedByOperator=>true;
        public string ResponseAttribution=>"J2534_DRIVER_MANAGED_SELECTED_HEADER";
        public string ResponseHexRepresentation=>"NORMALIZED_APPLICATION_PAYLOAD";
        public bool ExecutionCompleted=>Result.Completed&&TransportProblem is null;
        public bool PhysicalVehicleVerified=>false;
        public bool WholeVehicleCoverageVerified=>false;
    }

    internal static Options Parse(string[] args)
    {
        if(args.Length==0||args[0]!=Command)throw new ArgumentException("Expected legacy freeze command");
        var values=new Dictionary<string,string>(StringComparer.Ordinal);
        var frames=new List<int>();var pids=new List<int>();
        bool profile=false,scope=false;
        for(int i=1;i<args.Length;i++)
        {
            string key=args[i];
            if(key=="--confirm-legacy-profile")
            {if(profile)throw new ArgumentException("Duplicate legacy confirmation");profile=true;continue;}
            if(key=="--confirm-functional-scope")
            {if(scope)throw new ArgumentException("Duplicate scope confirmation");scope=true;continue;}
            if(key is not ("--adapter-index" or "--variant" or "--source" or "--vin" or "--frame" or
                "--pid" or "--budget-ms" or "--output")||i+1>=args.Length||args[i+1].StartsWith("--",StringComparison.Ordinal))
                throw new ArgumentException("Unknown or incomplete legacy freeze option");
            string value=args[++i];
            if(key is "--frame" or "--pid")
            {
                if(value.Length is <1 or >2||!byte.TryParse(value,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out byte id))
                    throw new ArgumentException("Expected hexadecimal byte");
                var list=key=="--frame"?frames:pids;
                if(list.Count>=(key=="--frame"?8:64))throw new ArgumentException("Too many selected identifiers");
                list.Add(id);
            }
            else if(!values.TryAdd(key,value))throw new ArgumentException("Duplicate legacy freeze option");
        }
        string Required(string key)=>values.TryGetValue(key,out var value)&&!string.IsNullOrWhiteSpace(value)
            ?value:throw new ArgumentException("Missing "+key);
        int Number(string key,int? fallback=null)
        {
            var value=values.GetValueOrDefault(key);
            if(value is null&&fallback.HasValue)return fallback.Value;
            if(!int.TryParse(value,NumberStyles.None,CultureInfo.InvariantCulture,out int result))
                throw new ArgumentException("Invalid numeric option");
            return result;
        }
        int adapter=Number("--adapter-index"),variant=Number("--variant");
        string sourceHex=Required("--source");
        if(!profile||!scope||adapter<0||variant is <1 or >5||sourceHex.Length!=2||
           !byte.TryParse(sourceHex,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out byte source)||
           source is 0 or 0xf1 or 0xff)
            throw new ArgumentException("Explicit legacy profile, source and functional confirmation required");
        var plan=new ObdJ1979FreezeCampaign.Plan(Required("--vin"),frames.ToArray(),pids.ToArray(),
            Number("--budget-ms",180000)).Validate();
        return new(adapter,(ObdLegacyJ2534Transport.Variant)variant,source,plan,Required("--output"));
    }

    internal static Report Execute(J2534Adapter adapter,Options options,Action requireCurrent)
    {
        var plan=options.Plan.Validate();
        var (id,baud)=ObdLegacyJ2534Transport.Profile(options.Variant);
        var started=DateTimeOffset.UtcNow;
        ObdJ1979FreezeCampaign.Result? result=null;
        IReadOnlyList<ObdJ1979LegacyLiveLink.Trace> trace=[];
        string? initialization=null,problem=null;
        try
        {
            requireCurrent();
            using var transport=ObdLegacyJ2534Transport.Open(adapter,options.Variant,options.Source,requireCurrent);
            initialization=transport.InitEvidence;
            (result,trace)=transport.RunFreeze(plan);
        }
        catch(OperationCanceledException){problem="CANCELLED";}
        catch(Exception e) when(e is IOException or InvalidDataException or InvalidOperationException or ArgumentException or
            TimeoutException or DllNotFoundException or EntryPointNotFoundException or BadImageFormatException or
            System.Runtime.InteropServices.SEHException)
        {problem="TRANSPORT_ERROR";}
        result??=ObdJ1979FreezeCampaign.Execute(plan,_=>new(
            problem=="CANCELLED"?"CANCELLED":"CONTEXT_CHANGED",null,0,false));
        return new("camarodiag-functional-j1979-legacy-freeze-v1","SAE-J1979","WINDOWS_J2534_LEGACY",
            typeof(ObdJ1979LegacyFreezeCli).Assembly.GetName().Version?.ToString()??"unknown",
            adapter.DisplayName,(int)options.Variant,options.Source.ToString("X2"),id,baud,initialization,
            started,DateTimeOffset.UtcNow,problem,result,trace);
    }

    internal static string Encode(Report report)=>JsonSerializer.Serialize(report,new JsonSerializerOptions
    {PropertyNamingPolicy=JsonNamingPolicy.SnakeCaseLower,WriteIndented=true});

    private static void Write(FileStream output,Report report)
    {
        using var writer=new StreamWriter(output,leaveOpen:true);
        writer.WriteLine(Encode(report));writer.Flush();output.Flush(true);
    }
    internal static void WriteNew(string path,Report report)
    {
        using var output=new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        Write(output,report);
    }

    internal static int Run(string[] args)
    {
        try
        {
            var options=Parse(args);var adapters=J2534Registry.Discover();
            if(options.AdapterIndex>=adapters.Count)throw new ArgumentException("Adapter index out of range");
            using var output=new FileStream(options.Output,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            using var cancellation=new CancellationTokenSource();
            ConsoleCancelEventHandler handler=(_,e)=>{e.Cancel=true;cancellation.Cancel();};
            Console.CancelKeyPress+=handler;
            try
            {
                var result=Execute(adapters[options.AdapterIndex],options,cancellation.Token.ThrowIfCancellationRequested);
                Write(output,result);
                foreach(var frame in result.Result.Frames)
                    Console.WriteLine($"{frame.Frame:X2} [{frame.Status}] {frame.TriggerDtc} {frame.Detail}");
                return result.ExecutionCompleted&&result.Result.AllFramesStable?0:7;
            }
            finally{Console.CancelKeyPress-=handler;}
        }
        catch(Exception e) when(e is ArgumentException or IOException or UnauthorizedAccessException)
        {Console.Error.WriteLine(e.Message);return 6;}
    }
}
