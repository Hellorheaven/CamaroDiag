using System.Diagnostics;
using System.Globalization;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979CanNetworkCli
{
    internal const string Command="--j1979-network";
    internal sealed record Options(int AdapterIndex,int Profile,bool DiscoverOnly,uint[] Sources,string? Vin,
        int DiscoveryMs,int BudgetSeconds,string Output);
    internal sealed record Report(string Schema,string Protocol,string Version,string Adapter,int Profile,
        uint Baud,bool PhysicalVehicleVerified,DateTimeOffset StartedAtUtc,DateTimeOffset FinishedAtUtc,
        ObdJ1979CanNetwork.Discovery? Discovery,ObdJ1979CanNetwork.NetworkResult? Network,string? Error)
    {
        public bool Completed=>Error is null&&(Network?.Completed??Discovery?.Completed??false);
        public bool WholeVehicleCoverageVerified=>false;
        public string[] NotExecuted=>["MODE_04_CLEAR","MODE_08_CONTROL"];
    }
    internal static Options Parse(string[] args)
    {
        if(args.Length<2||!args[0].Equals(Command,StringComparison.OrdinalIgnoreCase))throw new ArgumentException("Expected dedicated J1979 network command.");
        var values=new Dictionary<string,string>(StringComparer.Ordinal);
        var flags=new HashSet<string>(StringComparer.Ordinal);
        var source=new List<uint>();
        for(int i=1;i<args.Length;i++){
            var key=args[i];
            if(key is "--discover-only" or "--confirm-functional-pid00" or "--confirm-physical-channels"){
                if(!flags.Add(key))throw new ArgumentException("Duplicate confirmation.");continue;
            }
            if(key is not ("--adapter-index" or "--protocol" or "--source" or "--vin" or "--discovery-ms" or "--budget-seconds" or "--output")||
                i+1>=args.Length||args[i+1].StartsWith("--",StringComparison.Ordinal))throw new ArgumentException("Unexpected option.");
            var value=args[++i];
            if(key=="--source"){
                if(value.Length is <1 or >8||!uint.TryParse(value,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out uint id)||
                    source.Count>=32)throw new ArgumentException("Invalid response CAN ID.");
                source.Add(id);
            }else if(!values.TryAdd(key,value))throw new ArgumentException("Duplicate option.");
        }
        int Number(string key,int? fallback=null){
            if(!values.TryGetValue(key,out var text)&&fallback.HasValue)return fallback.Value;
            if(!int.TryParse(text,NumberStyles.None,CultureInfo.InvariantCulture,out var n))throw new ArgumentException("Expected integer: "+key);
            return n;
        }
        var index=Number("--adapter-index");var profile=Number("--protocol");
        var discovery=Number("--discovery-ms",1500);var budget=Number("--budget-seconds",180);
        var only=flags.Contains("--discover-only");var vin=values.GetValueOrDefault("--vin");
        var output=values.GetValueOrDefault("--output");
        if(index<0||discovery is <100 or >30000||budget is <1 or >600||discovery>budget*1000||
            string.IsNullOrWhiteSpace(output)||!flags.Contains("--confirm-functional-pid00")||
            only==(source.Count>0)||source.Count>0&&!flags.Contains("--confirm-physical-channels")||
            vin is not null&&(only||!System.Text.RegularExpressions.Regex.IsMatch(vin,@"\A[A-HJ-NPR-Z0-9]{17}\z")))
            throw new ArgumentException("Documented profile, discovery scope, confirmations and new output required.");
        return new(index,profile,only,ObdJ1979CanNetwork.ValidateTargets(source,ObdJ1979CanNetwork.Extended(profile)),
            vin,discovery,budget,output);
    }
    internal static async Task<Report> ExecuteAsync(J2534Adapter adapter,Options options,
        CancellationToken token,Action requireActive)
    {
        var started=DateTimeOffset.UtcNow;var watch=Stopwatch.StartNew();
        ObdJ1979CanNetwork.Discovery? discovery=null;
        ObdJ1979CanNetwork.NetworkResult? network=null;
        string? error=null;
        void Guard(){token.ThrowIfCancellationRequested();requireActive();}
        try{
            discovery=await ObdJ1979CanNetwork.DiscoverVendorAsync(adapter,options.Profile,
                TimeSpan.FromMilliseconds(options.DiscoveryMs),token,Guard);
            if(!options.DiscoverOnly){
                var remaining=TimeSpan.FromSeconds(options.BudgetSeconds)-watch.Elapsed;
                if(remaining<=TimeSpan.Zero)error="BUDGET_EXHAUSTED";
                else network=ObdJ1979CanNetwork.ScanVendor(adapter,options.Profile,discovery,options.Sources,
                    options.Vin,remaining,Guard);
            }
        }catch(OperationCanceledException){error="CANCELLED";}
        catch(Exception e) when(e is IOException or InvalidDataException or ArgumentException or InvalidOperationException or
            TimeoutException or DllNotFoundException or EntryPointNotFoundException or BadImageFormatException or
            System.Runtime.InteropServices.SEHException){error=e.GetType().Name;}
        return new(options.DiscoverOnly?"camarodiag-functional-j1979-can-discovery-v1":"camarodiag-functional-j1979-can-network-v1",
            "SAE-J1979",typeof(ObdJ1979CanNetworkCli).Assembly.GetName().Version?.ToString()??"",
            adapter.DisplayName,options.Profile,ObdJ1979CanNetwork.Baud(options.Profile),false,started,DateTimeOffset.UtcNow,
            discovery,network,error);
    }
    internal static string Encode(Report report)=>JsonSerializer.Serialize(report,new JsonSerializerOptions{
        WriteIndented=true,PropertyNamingPolicy=JsonNamingPolicy.CamelCase});
    internal static void WriteNew(string path,Report report){
        using var stream=new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        Write(stream,report);
    }
    private static void Write(FileStream stream,Report report){
        using var writer=new StreamWriter(stream,leaveOpen:true);
        writer.WriteLine(Encode(report));writer.Flush();stream.Flush(true);
    }
    internal static async Task<int> RunAsync(string[] args)
    {
        try{
            var options=Parse(args);
            var adapters=J2534Registry.Discover();
            if(options.AdapterIndex>=adapters.Count)throw new ArgumentException("Adapter index out of range.");
            using var stream=new FileStream(options.Output,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            using var cancel=new CancellationTokenSource();
            ConsoleCancelEventHandler handler=(_,e)=>{e.Cancel=true;cancel.Cancel();};
            Console.CancelKeyPress+=handler;
            try{
                var report=await ExecuteAsync(adapters[options.AdapterIndex],options,cancel.Token,()=>{});
                Write(stream,report);
                foreach(var row in report.Discovery?.Responders??[])
                    Console.WriteLine($"{row.RxCanId} [{row.Status}] {row.Detail}");
                foreach(var row in report.Network?.EcuReports??[])
                    Console.WriteLine($"{row.RxCanId} [{row.Status}] {row.Error}");
                return report.Completed?0:7;
            }finally{Console.CancelKeyPress-=handler;}
        }catch(Exception e) when(e is ArgumentException or IOException or UnauthorizedAccessException){
            Console.Error.WriteLine("J1979 network: "+e.Message);return 6;
        }
    }
}
