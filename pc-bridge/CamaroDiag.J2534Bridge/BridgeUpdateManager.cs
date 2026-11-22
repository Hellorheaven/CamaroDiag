using System.Diagnostics;
using System.Net.Http.Headers;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Native updater for the official CamaroDiag Windows setup packages.
/// The UI initiates checks and confirms an update; neither a third-party
/// ECU programmer nor an arbitrary installer URL can be invoked.
/// </summary>
internal static class BridgeUpdateManager
{
    private const string Api = "https://api.github.com/repos/Hellorheaven/CamaroDiag/releases/latest";
    private const long MaximumInstallerBytes = 256L * 1024 * 1024;
    private static readonly HttpClient Http = CreateHttp();

    internal sealed record Candidate(Version Current, Version Latest,
        string Architecture, Uri InstallerUrl, string FileName);

    internal static Version InstalledVersion
    {
        get
        {
            var version = Assembly.GetExecutingAssembly().GetName().Version ??
                new Version(0, 0, 0);
            return new Version(version.Major, version.Minor, Math.Max(0, version.Build));
        }
    }

    private static HttpClient CreateHttp()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromMinutes(2) };
        client.DefaultRequestHeaders.UserAgent.Add(
            new ProductInfoHeaderValue("CamaroDiag-J2534-Bridge", "1.0"));
        client.DefaultRequestHeaders.Accept.Add(
            new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        return client;
    }

    internal static async Task<Candidate?> CheckLatestAsync(
        CancellationToken cancellationToken = default)
    {
        var arch = RuntimeInformation.ProcessArchitecture switch
        {
            Architecture.X64 => "win-x64",
            Architecture.X86 => "win-x86",
            _ => null
        };
        if (arch is null) return null;
        using var response = await Http.GetAsync(Api, cancellationToken);
        response.EnsureSuccessStatusCode();
        await using var remote = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(remote,
            cancellationToken: cancellationToken);
        var root = document.RootElement;
        if (root.GetProperty("draft").GetBoolean() ||
            root.GetProperty("prerelease").GetBoolean())
            return null;

        var tag = root.GetProperty("tag_name").GetString()?.TrimStart('v', 'V');
        if (!Version.TryParse(tag, out var release))
            throw new InvalidDataException("Latest CamaroDiag release has no valid version.");
        var latest = new Version(release.Major, release.Minor,
            Math.Max(0, release.Build));
        var current = InstalledVersion;
        if (latest <= current) return null;

        var expected = $"CamaroDiag-J2534-Bridge-Setup-release-{arch}.exe";
        foreach (var asset in root.GetProperty("assets").EnumerateArray())
        {
            if (!string.Equals(asset.GetProperty("name").GetString(),
                expected, StringComparison.Ordinal)) continue;
            var uriText = asset.GetProperty("browser_download_url").GetString();
            if (!Uri.TryCreate(uriText, UriKind.Absolute, out var uri) ||
                uri.Scheme != Uri.UriSchemeHttps ||
                !string.Equals(uri.Host, "github.com", StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException(
                    "Refusing an update installer outside GitHub HTTPS.");
            return new Candidate(current, latest, arch, uri, expected);
        }
        return null; // No compatible official Windows installer in this release.
    }

    internal static async Task<string> DownloadAndScheduleInstallAsync(
        Candidate candidate, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(candidate);
        if (candidate.Latest <= InstalledVersion ||
            candidate.InstallerUrl.Scheme != Uri.UriSchemeHttps ||
            !string.Equals(candidate.InstallerUrl.Host,
                "github.com", StringComparison.OrdinalIgnoreCase) ||
            candidate.Architecture is not ("win-x64" or "win-x86") ||
            candidate.FileName !=
                $"CamaroDiag-J2534-Bridge-Setup-release-{candidate.Architecture}.exe")
            throw new UnauthorizedAccessException("Unapproved CamaroDiag Windows update package.");

        var temp = Path.Combine(Path.GetTempPath(),
            $"CamaroDiag-Bridge-{candidate.Architecture}-{candidate.Latest}-{Guid.NewGuid():N}.exe");
        var script = Path.Combine(Path.GetTempPath(),
            $"CamaroDiag-Bridge-Update-{Guid.NewGuid():N}.ps1");

        var started = false;
        try
        {
            using (var response = await Http.GetAsync(candidate.InstallerUrl,
                HttpCompletionOption.ResponseHeadersRead, cancellationToken))
            {
                response.EnsureSuccessStatusCode();
                if (response.Content.Headers.ContentLength > MaximumInstallerBytes)
                    throw new InvalidDataException("Installer exceeds the maximum allowed size.");
                await using var stream = new FileStream(temp, FileMode.CreateNew,
                    FileAccess.Write, FileShare.None, 65536, FileOptions.WriteThrough);
                await using var remote = await response.Content.ReadAsStreamAsync(cancellationToken);
                var buffer = new byte[65536];
                long total = 0;
                while (true)
                {
                    var count = await remote.ReadAsync(buffer, cancellationToken);
                    if (count == 0) break;
                    total += count;
                    if (total > MaximumInstallerBytes)
                        throw new InvalidDataException("Installer exceeds the maximum allowed size.");
                    await stream.WriteAsync(buffer.AsMemory(0, count), cancellationToken);
                }
                await stream.FlushAsync(cancellationToken);
                stream.Flush(flushToDisk: true);
                if (total < 1024)
                    throw new InvalidDataException("Downloaded installer is implausibly small.");
            }

            // A downloaded error page must not be launched as a Windows setup.
            using (var binary = File.OpenRead(temp))
            {
                if (binary.ReadByte() != 'M' || binary.ReadByte() != 'Z')
                    throw new InvalidDataException("Downloaded file is not a Windows executable.");
            }

            var destination = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
            var log = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "CamaroDiag", "Bridge-update.log");
            Directory.CreateDirectory(Path.GetDirectoryName(log)!);

            static string Ps(string value) => value.Replace("'", "''");
            var code = "$ErrorActionPreference='Stop'\n" +
                $"$log='{Ps(log)}'\n" +
                "try {\n" +
                $"  Wait-Process -Id {Environment.ProcessId} -ErrorAction SilentlyContinue\n" +
                $"  $installer='{Ps(temp)}'\n" +
                $"  $target='{Ps(destination)}'\n" +
                "  $arguments=@('/SILENT','/NORESTART',('/DIR=' + [char]34 + $target + [char]34))\n" +
                "  $result=Start-Process -FilePath $installer -ArgumentList $arguments -PassThru -Wait\n" +
                "  if ($result.ExitCode -ne 0) { throw ('Installer exit code: ' + $result.ExitCode) }\n" +
                "  Set-Content -Path $log -Value 'Mise à jour CamaroDiag installée.'\n" +
                "} catch { Set-Content -Path $log -Value ('Échec mise à jour : ' + $_.Exception.Message) }\n" +
                $"finally {{ Remove-Item '{Ps(temp)}' -Force -ErrorAction SilentlyContinue; " +
                $"Remove-Item '{Ps(script)}' -Force -ErrorAction SilentlyContinue }}\n";
            await File.WriteAllTextAsync(script, code, Encoding.UTF8, cancellationToken);

            var start = new ProcessStartInfo("powershell.exe")
            {
                UseShellExecute = false, CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
                WorkingDirectory = destination
            };
            foreach (var arg in new[]
            {
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script
            })
                start.ArgumentList.Add(arg);
            var process = Process.Start(start);
            if (process is null)
                throw new IOException("Could not launch the Windows update assistant.");
            process.Dispose();
            started = true;
            return log; // Caller must now exit so the setup can replace files.
        }
        finally
        {
            if (!started)
            {
                try { File.Delete(temp); } catch (IOException) { }
                try { File.Delete(script); } catch (IOException) { }
            }
        }
    }

    // Backward-compatible explicit CLI mode. Default GUI startup does not
    // ask questions in a hidden console or perform unsolicited updates.
    public static async Task<bool> CheckAndOfferUpdateAsync()
    {
        try
        {
            var candidate = await CheckLatestAsync();
            if (candidate is null) return false;
            Console.WriteLine($"CamaroDiag Windows: {candidate.Current} -> {candidate.Latest}");
            Console.Write("Download and install official update? [y/N]: ");
            if (Console.ReadLine()?.Trim().ToLowerInvariant() is not ("y" or "yes"))
                return false;
            await DownloadAndScheduleInstallAsync(candidate);
            return true;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("Update check unavailable: " + ex.Message);
            return false;
        }
    }
}
