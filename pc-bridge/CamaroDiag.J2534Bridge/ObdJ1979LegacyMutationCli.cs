using System.Globalization;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979LegacyMutationCli
{
    internal const string Command = "--j1979-legacy-mutation";
    internal sealed record Options(int AdapterIndex, ObdLegacyJ2534Transport.Variant Variant,
        ObdJ1979LegacyMutation.Operation Operation, string Vin, byte EngineSource,
        byte[] Sources, string Output);

    internal sealed record Report(string Schema, string Protocol, string Version, string Adapter,
        string Variant, string Operation, string Scope, string ExpectedVin, string EngineSource,
        string[] DocumentedSources, DateTimeOffset StartedAtUtc, DateTimeOffset FinishedAtUtc,
        string? InitEvidence, ObdJ1979LegacyMutation.Result? Result, string? Error);

    internal static byte ParseSource(string text)
    {
        if (text.Length != 2 || !byte.TryParse(text, NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture, out var value) || value is 0 or 0xF1 or 0xFF)
            throw new ArgumentException("Invalid documented ECU source.");
        return value;
    }

    internal static byte[] ParseSources(string text)
    {
        var values = text.Split(',').Select(x => ParseSource(x.Trim())).ToArray();
        if (values.Length is < 1 or > 32 || values.Distinct().Count() != values.Length)
            throw new ArgumentException("Documented unique ECU sources required.");
        return values;
    }

    internal static Options Parse(string[] args)
    {
        if (args.Length != 15 || args[0] != Command)
            throw new ArgumentException("Explicit legacy mutation options required.");
        var values = new Dictionary<string,string>();
        for(int i=1;i<args.Length;i+=2)
        {
            if(args[i] is not ("--adapter-index" or "--variant" or "--operation" or "--vin" or
                "--engine-source" or "--sources" or "--output") || args[i+1].StartsWith("--") ||
                !values.TryAdd(args[i],args[i+1]))
                throw new ArgumentException("Unexpected or duplicate option.");
        }
        string Get(string key) => values.TryGetValue(key,out var value) ? value :
            throw new ArgumentException("Missing option: " + key);
        if (!int.TryParse(Get("--adapter-index"), NumberStyles.None, CultureInfo.InvariantCulture, out int index) ||
            !int.TryParse(Get("--variant"), NumberStyles.None, CultureInfo.InvariantCulture, out int variant) ||
            variant is < 1 or > 5)
            throw new ArgumentException("Invalid adapter or variant.");
        var operation = Get("--operation") switch
        {
            "clear" => ObdJ1979LegacyMutation.Operation.Clear,
            "evap" => ObdJ1979LegacyMutation.Operation.EvapPreparation,
            _ => throw new ArgumentException("Unknown operation.")
        };
        string vin = Get("--vin");
        if (!System.Text.RegularExpressions.Regex.IsMatch(vin, @"\A[A-HJ-NPR-Z0-9]{17}\z"))
            throw new ArgumentException("Documented VIN required.");
        byte engine = ParseSource(Get("--engine-source"));
        byte[] sources = ParseSources(Get("--sources"));
        if (!sources.Contains(engine) || string.IsNullOrWhiteSpace(Get("--output")))
            throw new ArgumentException("Engine source must belong to documented scope; output required.");
        return new(index,(ObdLegacyJ2534Transport.Variant)variant,operation,vin,engine,sources,Get("--output"));
    }

    internal static string Encode(Report report) => JsonSerializer.Serialize(report,
        new JsonSerializerOptions { WriteIndented = true, PropertyNamingPolicy = JsonNamingPolicy.CamelCase });

    internal static Report Execute(J2534Adapter adapter, Options options, bool warning, bool yes,
        Action requireActive)
    {
        if (!warning || !yes) throw new UnauthorizedAccessException("Separate operator approvals required.");
        // Exclusive output reservation happens before opening the driver.
        using var output = new FileStream(options.Output, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        using var writer = new StreamWriter(output);
        var started = DateTimeOffset.UtcNow;
        string? init = null, error = null;
        ObdJ1979LegacyMutation.Result? result = null;
        Report Make(string schema, ObdJ1979LegacyMutation.Result? evidence) => new(schema,"SAE-J1979",
            typeof(ObdJ1979LegacyMutationCli).Assembly.GetName().Version?.ToString() ?? "",
            adapter.DisplayName, options.Variant.ToString(), options.Operation.ToString(),
            "FUNCTIONAL_VEHICLE_WIDE", options.Vin, options.EngineSource.ToString("X2"),
            options.Sources.Select(x=>x.ToString("X2")).ToArray(),started,DateTimeOffset.UtcNow,init,evidence,error);
        try
        {
            requireActive();
            using var session = ObdLegacyJ2534Transport.Open(adapter, options.Variant, null, requireActive);
            init = session.InitEvidence;
            result = session.RunVehicleMutation(options.Operation,options.Vin,options.EngineSource,
                options.Sources,warning,yes, baseline =>
                {
                    // A separate durable baseline cannot be overwritten by final results.
                    using var saved = new FileStream(options.Output + ".baseline.json", FileMode.CreateNew,
                        FileAccess.Write, FileShare.None);
                    using var baselineWriter = new StreamWriter(saved);
                    baselineWriter.WriteLine(Encode(Make("camarodiag-j1979-legacy-baseline-v1",baseline)));
                    baselineWriter.Flush();
                    saved.Flush(true);
                });
        }
        catch (Exception failure) when (failure is IOException or UnauthorizedAccessException or
            InvalidOperationException or ArgumentException or InvalidDataException or OperationCanceledException or
            TimeoutException or DllNotFoundException or BadImageFormatException or EntryPointNotFoundException)
        { error = failure.GetType().Name; }
        var report = Make("camarodiag-functional-j1979-legacy-mutation-v1",result);
        writer.WriteLine(Encode(report));
        writer.Flush();
        output.Flush(true);
        return report;
    }

    internal static int Run(string[] args)
    {
        try
        {
            var options = Parse(args);
            if (Console.IsInputRedirected) throw new ArgumentException("Interactive confirmation required.");
            Console.WriteLine("FUNCTIONAL VEHICLE-WIDE request: other ECUs, including silent ECUs, may be affected.");
            Console.WriteLine(options.Operation == ObdJ1979LegacyMutation.Operation.Clear ?
                "May erase emissions DTCs, freeze frames and readiness. Export original full scan first." :
                "Requests preparation of EVAP leak-test conditions; does not perform the leak test.");
            Console.WriteLine("Follow manufacturer procedure, vehicle stationary, engine stopped, safe conditions. No retry.");
            Console.Write("Type OK to acknowledge: ");
            if (Console.ReadLine() != "OK") return 6;
            Console.Write($"VIN {options.Vin}, {options.Variant}, scope ALL responding and potentially silent ECUs. Type YES: ");
            if (Console.ReadLine() != "YES") return 6;
            var adapters = J2534Registry.Discover();
            if (options.AdapterIndex >= adapters.Count) throw new ArgumentException("Adapter index out of range.");
            using var cancel = new CancellationTokenSource();
            ConsoleCancelEventHandler handler = (_,e) => {e.Cancel=true;cancel.Cancel();};
            Console.CancelKeyPress += handler;
            try
            {
                var report = Execute(adapters[options.AdapterIndex],options,true,true,cancel.Token.ThrowIfCancellationRequested);
                Console.WriteLine(report.Result?.Outcome ?? report.Error ?? "NOT_TESTED");
                return report.Result?.Outcome is "ACKNOWLEDGED" or "ACKNOWLEDGED_READBACK" &&
                    report.Result.Reason != "DTCS_STILL_PRESENT" ? 0 : 7;
            }
            finally { Console.CancelKeyPress -= handler; }
        }
        catch (Exception failure) when(failure is IOException or UnauthorizedAccessException or ArgumentException)
        { Console.Error.WriteLine(failure.Message); return 6; }
    }
}
