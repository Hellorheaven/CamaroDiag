using System.Globalization;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979FreezeCli
{
    internal const string Command = "--j1979-freeze";
    internal sealed record Options(int AdapterIndex,int Profile,int Tx,int Rx,
        ObdJ1979FreezeCampaign.Plan Plan,string Output);
    internal sealed record Report(string Schema,string Protocol,string Transport,string Version,
        string Adapter,int Profile,string TxCanId,string RxCanId,
        bool PhysicalChannelConfirmedByOperator,DateTimeOffset StartedAtUtc,DateTimeOffset FinishedAtUtc,
        string? TransportProblem,ObdJ1979FreezeCampaign.Result Result)
    {
        public bool ExecutionCompleted => Result.Completed && TransportProblem is null;
    }

    internal static Options Parse(string[] args)
    {
        if (args.Length < 2 || !args[0].Equals(Command,StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Expected explicit freeze-frame command.");
        var values = new Dictionary<string,string>(StringComparer.Ordinal);
        var frames = new List<int>();
        var pids = new List<int>();
        bool confirmed = false;
        for (int i = 1; i < args.Length; i++)
        {
            var key = args[i];
            if (key == "--confirm-physical-channel")
            {
                if (confirmed) throw new ArgumentException("Duplicate confirmation.");
                confirmed = true;
                continue;
            }
            if (key is not ("--adapter-index" or "--protocol" or "--tx" or "--rx" or "--vin" or
                "--frame" or "--pid" or "--budget-ms" or "--output") ||
                i + 1 >= args.Length || args[i+1].StartsWith("--",StringComparison.Ordinal))
                throw new ArgumentException("Unexpected freeze-frame option.");
            var value = args[++i];
            if (key is "--frame" or "--pid")
            {
                if (value.Length is < 1 or > 2 ||
                    !byte.TryParse(value,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out var number))
                    throw new ArgumentException("Expected hexadecimal frame or PID.");
                var target = key == "--frame" ? frames : pids;
                int bound = key == "--frame" ? ObdJ1979FreezeCampaign.MaximumFrames : ObdJ1979FreezeCampaign.MaximumPids;
                if (target.Count >= bound) throw new ArgumentException("Too many selected identifiers.");
                target.Add(number);
            }
            else if (!values.TryAdd(key,value)) throw new ArgumentException("Duplicate option.");
        }
        int Number(string key,int? fallback = null)
        {
            if (!values.TryGetValue(key,out var value) && fallback.HasValue) return fallback.Value;
            if (!int.TryParse(value,NumberStyles.None,CultureInfo.InvariantCulture,out var number))
                throw new ArgumentException("Expected integer: " + key);
            return number;
        }
        int Id(string key)
        {
            if (!values.TryGetValue(key,out var value) || value.Length is < 1 or > 8 ||
                !int.TryParse(value,NumberStyles.AllowHexSpecifier,CultureInfo.InvariantCulture,out var id) ||
                id < 0) throw new ArgumentException("Expected physical CAN ID.");
            return id;
        }
        int index = Number("--adapter-index"), profile = Number("--protocol");
        int tx = Id("--tx"), rx = Id("--rx");
        var output = values.GetValueOrDefault("--output");
        if (index < 0 || !confirmed || !StandardObdPhysicalPolicy.SupportsChannel(tx,rx,profile) ||
            string.IsNullOrWhiteSpace(output))
            throw new ArgumentException("Documented physical channel, confirmation and output required.");
        var plan = new ObdJ1979FreezeCampaign.Plan(values.GetValueOrDefault("--vin") ?? "",
            frames.ToArray(),pids.ToArray(),Number("--budget-ms",180000)).Validate();
        return new(index,profile,tx,rx,plan,output);
    }

    internal static Report Execute(J2534Adapter adapter,Options options,Action requireCurrent)
    {
        var started = DateTimeOffset.UtcNow;
        var plan = options.Plan.Validate();
        if (!StandardObdPhysicalPolicy.SupportsChannel(options.Tx,options.Rx,options.Profile))
            throw new ArgumentException("Physical channel required.");
        string? problem = null;
        ObdJ1979FreezeCampaign.Result? result = null;
        try
        {
            requireCurrent();
            using var device = new J2534Device(adapter);
            device.Open(options.Tx,options.Rx,ObdJ1979CanNetwork.Extended(options.Profile),options.Profile);
            requireCurrent();
            result = device.RunStandardFreezeCampaign(plan,requireCurrent);
        }
        catch (OperationCanceledException) { problem = "CANCELLED"; }
        catch (Exception e) when (e is IOException or InvalidDataException or ArgumentException or
            InvalidOperationException or TimeoutException or DllNotFoundException or EntryPointNotFoundException or
            BadImageFormatException or System.Runtime.InteropServices.SEHException)
        { problem = "TRANSPORT_ERROR"; }
        result ??= ObdJ1979FreezeCampaign.Execute(plan,_ => new(
            problem == "CANCELLED" ? "CANCELLED" : "CONTEXT_CHANGED",null,0,false));
        return new("camarodiag-functional-j1979-freeze-v1","SAE-J1979","WINDOWS_J2534_ISOTP",
            typeof(ObdJ1979FreezeCli).Assembly.GetName().Version?.ToString() ?? "unknown",
            adapter.DisplayName,options.Profile,options.Tx.ToString("X"),options.Rx.ToString("X"),
            true,started,DateTimeOffset.UtcNow,problem,result);
    }

    internal static string Encode(Report report) => JsonSerializer.Serialize(report,new JsonSerializerOptions
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        WriteIndented = true
    });

    private static void Write(FileStream stream,Report report)
    {
        using var writer = new StreamWriter(stream,leaveOpen:true);
        writer.WriteLine(Encode(report));
        writer.Flush();
        stream.Flush(true);
    }

    internal static void WriteNew(string path,Report report)
    {
        using var stream = new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.None);
        Write(stream,report);
    }

    internal static int Run(string[] args)
    {
        try
        {
            var options = Parse(args);
            var adapters = J2534Registry.Discover();
            if (options.AdapterIndex >= adapters.Count) throw new ArgumentException("Adapter index out of range.");
            using var output = new FileStream(options.Output,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            using var cancel = new CancellationTokenSource();
            ConsoleCancelEventHandler handler = (_,e) => { e.Cancel = true; cancel.Cancel(); };
            Console.CancelKeyPress += handler;
            try
            {
                var report = Execute(adapters[options.AdapterIndex],options,cancel.Token.ThrowIfCancellationRequested);
                Write(output,report);
                foreach (var frame in report.Result.Frames)
                    Console.WriteLine($"{frame.Frame:X2} [{frame.Status}] {frame.TriggerDtc} {frame.Detail}");
                return report.ExecutionCompleted && report.Result.AllFramesStable ? 0 : 7;
            }
            finally { Console.CancelKeyPress -= handler; }
        }
        catch (Exception e) when (e is ArgumentException or IOException or UnauthorizedAccessException)
        {
            Console.Error.WriteLine("J1979 freeze: " + e.Message);
            return 6;
        }
    }
}
