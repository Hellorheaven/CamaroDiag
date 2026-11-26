using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979LegacyCli
{
    internal const string Command = "--j1979-legacy";
    internal sealed record Options(int AdapterIndex, ObdLegacyJ2534Transport.Variant Variant,
        byte? Source, string Output, int BudgetSeconds);
    internal sealed record Report(string Schema, string Protocol, string Version, string Adapter,
        string Variant, uint J2534ProtocolId, uint Baud, string? SelectedResponseSource,
        string? InitializationEvidence, DateTimeOffset StartedAtUtc, DateTimeOffset FinishedAtUtc,
        bool PhysicalVehicleVerified, ObdJ1979LegacyScan.Result? Result, string? Error,
        ObdJ1979LegacyDiscovery.Result? Discovery = null);

    internal static Options Parse(string[] args)
    {
        if (args.Length < 2 || !args[0].Equals(Command, StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Expected dedicated legacy command.");
        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        bool confirmed = false;
        for (int i = 1; i < args.Length; i++)
        {
            var key = args[i];
            if (key == "--confirm-legacy-profile" && !confirmed)
            {
                confirmed = true;
                continue;
            }
            if (key is not ("--adapter-index" or "--variant" or "--source" or "--output" or "--budget-seconds") ||
                i + 1 >= args.Length || args[i + 1].StartsWith("--", StringComparison.Ordinal) ||
                !values.TryAdd(key, args[++i]))
                throw new ArgumentException("Unexpected or duplicate legacy argument.");
        }
        if (!confirmed ||
            !values.TryGetValue("--adapter-index", out var indexText) ||
            !int.TryParse(indexText, NumberStyles.None, CultureInfo.InvariantCulture, out int index) || index < 0 ||
            !values.TryGetValue("--variant", out var variantText) ||
            !int.TryParse(variantText, NumberStyles.None, CultureInfo.InvariantCulture, out int number) || number is < 1 or > 5 ||
            !values.TryGetValue("--source", out var sourceText) ||
            !values.TryGetValue("--output", out var output) || string.IsNullOrWhiteSpace(output))
            throw new ArgumentException("Documented legacy variant, adapter, response source and new JSON path required.");
        byte? source = null;
        if (!sourceText.Equals("discover", StringComparison.OrdinalIgnoreCase))
        {
            if (sourceText.Length != 2 || !byte.TryParse(sourceText, NumberStyles.AllowHexSpecifier,
                    CultureInfo.InvariantCulture, out byte parsed) || parsed is 0 or 0xF1 or 0xFF)
                throw new ArgumentException("Use a documented two-digit hex source or discover.");
            source = parsed;
        }
        int budget = 180;
        if (values.TryGetValue("--budget-seconds", out var budgetText) &&
            (!int.TryParse(budgetText, NumberStyles.None, CultureInfo.InvariantCulture, out budget) ||
             budget is < 1 or > 600))
            throw new ArgumentException("Invalid legacy budget.");
        return new(index, (ObdLegacyJ2534Transport.Variant)number, source, output, budget);
    }

    internal static Report Execute(J2534Adapter adapter, ObdLegacyJ2534Transport.Variant variant,
        byte? source, TimeSpan budget, Action requireActive)
    {
        var (id, baud) = ObdLegacyJ2534Transport.Profile(variant);
        var started = DateTimeOffset.UtcNow;
        ObdJ1979LegacyScan.Result? result = null;
        ObdJ1979LegacyDiscovery.Result? discovery = null;
        string? initialization = null, error = null;
        try
        {
            using var session = ObdLegacyJ2534Transport.Open(adapter, variant, source, requireActive);
            initialization = session.InitEvidence;
            if (source.HasValue)
                result = ObdJ1979LegacyScan.Run(source.Value, session.Read, requireActive, budget);
            else discovery = ObdJ1979LegacyDiscovery.Run(session.Read, requireActive,
                (int)Math.Min(1800, budget.TotalMilliseconds));
        }
        catch (Exception failure) when (failure is IOException or InvalidDataException or
            InvalidOperationException or ArgumentException or TimeoutException or
            DllNotFoundException or BadImageFormatException or EntryPointNotFoundException or
            OperationCanceledException or SEHException)
        {
            error = failure.GetType().Name;
        }
        return new(source.HasValue ? "camarodiag-functional-j1979-legacy-v1" :
            "camarodiag-functional-j1979-legacy-discovery-v1", "SAE-J1979",
            typeof(ObdJ1979LegacyCli).Assembly.GetName().Version?.ToString() ?? "",
            adapter.DisplayName, variant.ToString(), id, baud, source?.ToString("X2"),
            initialization, started, DateTimeOffset.UtcNow, false, result, error, discovery);
    }

    internal static string Encode(Report report) => JsonSerializer.Serialize(report,
        new JsonSerializerOptions { WriteIndented = true, PropertyNamingPolicy = JsonNamingPolicy.CamelCase });

    internal static int Run(string[] args)
    {
        try
        {
            var options = Parse(args);
            var adapters = J2534Registry.Discover();
            if (options.AdapterIndex >= adapters.Count)
                throw new ArgumentException("Adapter index out of range.");
            // Reserve the new output before any vehicle communication.
            using var stream = new FileStream(options.Output, FileMode.CreateNew, FileAccess.Write, FileShare.None);
            using var writer = new StreamWriter(stream);
            using var cancellation = new CancellationTokenSource();
            ConsoleCancelEventHandler cancel = (_, e) => { e.Cancel = true; cancellation.Cancel(); };
            Console.CancelKeyPress += cancel;
            try
            {
                var report = Execute(adapters[options.AdapterIndex], options.Variant, options.Source,
                    TimeSpan.FromSeconds(options.BudgetSeconds), cancellation.Token.ThrowIfCancellationRequested);
                writer.WriteLine(Encode(report));
                writer.Flush();
                stream.Flush(true);
                if (report.Discovery is { } discovery)
                {
                    foreach (var source in discovery.Sources)
                        Console.WriteLine($"{source.ResponseSource} [{source.Status}] {source.Detail}");
                    return discovery.HasVerifiedSource ? 0 : 7;
                }
                Console.WriteLine(report.Result is null ? report.Error :
                    $"SAE J1979 {report.Variant}: {report.Result.Steps.Count} observations; {report.Result.StopReason ?? "campaign completed"}.");
                return report.Result is { Completed: true, TransportObserved: true } ? 0 : 7;
            }
            finally { Console.CancelKeyPress -= cancel; }
        }
        catch (Exception error) when (error is ArgumentException or IOException or UnauthorizedAccessException)
        {
            Console.Error.WriteLine("SAE J1979 legacy: " + error.Message);
            return 6;
        }
    }
}
