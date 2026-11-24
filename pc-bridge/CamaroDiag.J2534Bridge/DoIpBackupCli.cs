using System.Globalization;
using System.Net;

namespace CamaroDiag.J2534Bridge;

/// <summary>Shared Windows backup engine, without GUI or vendor driver dependencies.</summary>
internal static class DoIpBackupCli
{
    internal const string BackupCommand = "--doip-backup";
    internal const string VerifyCommand = "--doip-verify-backup";
    internal sealed record Options(bool VerifyOnly, string Profile, string Archive,
        string? Host, bool Tls, string? Certificate, int TimeoutMs);

    internal static Options Parse(string[] args)
    {
        var backup = args.Count(a => a.Equals(BackupCommand, StringComparison.OrdinalIgnoreCase));
        var verify = args.Count(a => a.Equals(VerifyCommand, StringComparison.OrdinalIgnoreCase));
        if (backup + verify != 1) throw new ArgumentException("Choose exactly one DoIP backup operation.");
        var allowed = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            { "--profile", "--archive" };
        if (backup == 1) allowed.UnionWith(new[] { "--host", "--tls", "--certificate",
            "--timeout-ms", "--confirm-read" });
        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        for (var i = 0; i < args.Length; i++)
        {
            var key = args[i];
            if (key.Equals(BackupCommand, StringComparison.OrdinalIgnoreCase) ||
                key.Equals(VerifyCommand, StringComparison.OrdinalIgnoreCase)) continue;
            if (!allowed.Contains(key) || i + 1 >= args.Length ||
                args[i + 1].StartsWith("--", StringComparison.Ordinal) ||
                string.IsNullOrWhiteSpace(args[i + 1]) || !values.TryAdd(key, args[++i]))
                throw new ArgumentException("Invalid or duplicate backup option: " + key);
        }
        string Get(string key) => values.TryGetValue(key, out var value) ? value :
            throw new ArgumentException("Missing option: " + key);
        var profile = Get("--profile");
        var archive = Get("--archive");
        if (!archive.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("The archive must be a ZIP file.");
        if (verify == 1) return new Options(true, profile, archive, null, false, null, 0);
        if (!bool.TryParse(Get("--confirm-read"), out var confirm) || !confirm)
            throw new ArgumentException("Explicit --confirm-read true is required.");
        var host = Get("--host");
        if (!IPAddress.TryParse(host, out var address) || address.Equals(IPAddress.Any) ||
            address.Equals(IPAddress.IPv6Any) || address.Equals(IPAddress.Broadcast) ||
            address.IsIPv6Multicast)
            throw new ArgumentException("Specify the unicast IP address of the DoIP gateway.");
        var tls = true;
        if (values.TryGetValue("--tls", out var mode) && !bool.TryParse(mode, out tls))
            throw new ArgumentException("Invalid TLS selection.");
        var certificate = values.GetValueOrDefault("--certificate");
        if (certificate is not null && (!tls || certificate.Length != 40 ||
            !certificate.All(Uri.IsHexDigit)))
            throw new ArgumentException("A 40-digit Windows client certificate thumbprint requires TLS.");
        var timeout = 3000;
        if (values.TryGetValue("--timeout-ms", out var text) &&
            (!int.TryParse(text, NumberStyles.None, CultureInfo.InvariantCulture, out timeout) ||
                timeout is < 100 or > 30000))
            throw new ArgumentException("Timeout must be 100..30000 milliseconds.");
        return new Options(false, profile, archive, address.ToString(), tls, certificate, timeout);
    }

    internal static async Task<int> RunAsync(string[] args)
    {
        using var cancellation = new CancellationTokenSource();
        void Cancel(object? sender, ConsoleCancelEventArgs e)
        {
            e.Cancel = true;
            cancellation.Cancel();
        }
        Console.CancelKeyPress += Cancel;
        try
        {
            var options = Parse(args);
            var scope = DoIpBackupScope.Load(options.Profile);
            if (options.VerifyOnly)
            {
                var result = DoIpScopedBackup.VerifyZip(options.Archive, scope, cancellation.Token);
                Console.WriteLine(string.Format(DoIpConsolePresentation.Text("BackupVerified"),
                    scope.Length, result.Sha256));
                return 0;
            }
            if (File.Exists(options.Archive))
                throw new IOException(DoIpConsolePresentation.Text("BackupExists"));
            var host = options.Host ?? throw new InvalidOperationException();
            var address = IPAddress.Parse(host);
            var timeout = TimeSpan.FromMilliseconds(options.TimeoutMs);
            var gateway = await DoIpVehicleDiscovery.DiscoverAsync(host, timeout, cancellation.Token);
            var capture = await DoIpScopedBackup.ReadObservedAsync(gateway, address, scope,
                options.Tls, timeout, cancellation.Token, (pass, count) =>
                    Console.WriteLine(string.Format(DoIpConsolePresentation.Text("BackupProgress"),
                        pass, count, scope.Length)), options.Certificate);
            DoIpScopedBackup.SaveNew(options.Archive, capture, cancellation.Token);
            Console.WriteLine(string.Format(DoIpConsolePresentation.Text("BackupSaved"),
                scope.Length, capture.Sha256, options.Archive));
            return 0;
        }
        catch (OperationCanceledException)
        {
            Console.Error.WriteLine(DoIpConsolePresentation.Text("Canceled"));
            return 130;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error.Message);
            return 1;
        }
        finally { Console.CancelKeyPress -= Cancel; }
    }
}
