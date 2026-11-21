using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Optional, operator-driven Windows handoff to an independently installed
/// PCM Hammer CLI. No GPL code or PCM kernel binary is included in CamaroDiag.
///
/// The local operator controls this command in a physical Windows console.
/// It is deliberately NOT reachable through the authenticated Android bridge,
/// which continues to reject arbitrary write/security UDS requests.
/// </summary>
internal static class PcmHammerProgrammingHandoff
{
    internal sealed record Configuration(
        string CliExecutable, string Image, string Device,
        string Vin, string ExpectedImageSha256, string Backup, string Mode);

    internal static Configuration Parse(IReadOnlyList<string> arguments)
    {
        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var required = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "--pcmhammer-exe", "--image", "--device", "--vin",
            "--sha256", "--backup", "--mode"
        };
        for (var i = 0; i < arguments.Count; i++)
        {
            var key = arguments[i];
            if (key.Equals("--pcmhammer-program", StringComparison.OrdinalIgnoreCase)) continue;
            if (!required.Contains(key) || !values.TryAdd(key, i + 1 < arguments.Count ? arguments[++i] : ""))
                throw new ArgumentException("Unknown, missing or duplicate PCM Hammer parameter: " + key);
        }
        if (!required.SetEquals(values.Keys) || values.Values.Any(string.IsNullOrWhiteSpace))
            throw new ArgumentException("PCM Hammer requires --pcmhammer-exe, --image, --device, --vin, --sha256, --backup and --mode.");
        var exe = Path.GetFullPath(values["--pcmhammer-exe"]);
        var image = Path.GetFullPath(values["--image"]);
        var backup = Path.GetFullPath(values["--backup"]);
        var vin = values["--vin"].Trim().ToUpperInvariant();
        var sha = values["--sha256"].Trim().ToUpperInvariant();
        var mode = values["--mode"].Trim().ToLowerInvariant();
        if (!Path.GetFileName(exe).Equals("PcmHammerCLI.exe", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("The external programmer must be named PcmHammerCLI.exe.");
        if (!Regex.IsMatch(vin, "^[A-HJ-NPR-Z0-9]{17}$"))
            throw new ArgumentException("A complete, valid 17-character target VIN is required.");
        if (!Regex.IsMatch(sha, "^[0-9A-F]{64}$"))
            throw new ArgumentException("The exact 64-character SHA-256 of the image is required.");
        if (mode is not ("calibration" or "full"))
            throw new ArgumentException("Mode must be calibration or full.");
        if (string.IsNullOrWhiteSpace(values["--device"]))
            throw new ArgumentException("An unambiguous, explicit PassThru device is required.");
        if (string.Equals(image, backup, StringComparison.OrdinalIgnoreCase) ||
            string.Equals(exe, image, StringComparison.OrdinalIgnoreCase) ||
            string.Equals(exe, backup, StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("The image, backup and programmer paths must all be different.");
        return new Configuration(exe, image, values["--device"].Trim(), vin, sha, backup, mode);
    }

    internal static bool IsExpectedController(string output, string vin) =>
        output.Contains(vin, StringComparison.OrdinalIgnoreCase) &&
        Regex.IsMatch(output, @"\bE39A?\b", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    internal static bool HashMatches(string path, string expectedSha256)
    {
        using var stream = File.OpenRead(path);
        var hash = SHA256.HashData(stream);
        return CryptographicOperations.FixedTimeEquals(
            hash, Convert.FromHexString(expectedSha256));
    }

    private static ProcessStartInfo StartInfo(Configuration config, params string[] parameters)
    {
        var info = new ProcessStartInfo(config.CliExecutable)
        {
            WorkingDirectory = Path.GetDirectoryName(config.CliExecutable)!,
            UseShellExecute = false,
            CreateNoWindow = false
        };
        foreach (var item in parameters) info.ArgumentList.Add(item);
        return info;
    }

    private static async Task<(int ExitCode, string Output)> CaptureAsync(
        Configuration config, params string[] args)
    {
        var start = StartInfo(config, args);
        start.RedirectStandardOutput = true;
        start.RedirectStandardError = true;
        using var child = Process.Start(start) ?? throw new IOException("Cannot launch PCM Hammer CLI.");
        var output = child.StandardOutput.ReadToEndAsync();
        var errors = child.StandardError.ReadToEndAsync();
        await child.WaitForExitAsync();
        return (child.ExitCode, (await output) + Environment.NewLine + (await errors));
    }

    private static async Task<bool> RunVisibleAsync(Configuration config, params string[] args)
    {
        using var process = Process.Start(StartInfo(config, args))
            ?? throw new IOException("Cannot launch PCM Hammer CLI.");
        await process.WaitForExitAsync();
        return process.ExitCode == 0;
    }

    private static async Task VerifyIdentityAsync(Configuration config)
    {
        var (status, output) = await CaptureAsync(config, "--identify-pcm", "--device", config.Device);
        Console.WriteLine(output);
        if (status != 0 || !IsExpectedController(output, config.Vin))
            throw new InvalidOperationException(
                "PCM Hammer did not positively identify the required E39/E39A and matching VIN. No write attempted.");
    }

    public static async Task<int> RunAsync(string[] args)
    {
        try
        {
            var config = Parse(args);
            if (!OperatingSystem.IsWindows())
                throw new PlatformNotSupportedException("PCM Hammer programming requires Windows.");
            if (Console.IsInputRedirected)
                throw new InvalidOperationException("Physical operator confirmation in a Windows console is mandatory.");
            if (!File.Exists(config.CliExecutable) || !File.Exists(config.Image))
                throw new FileNotFoundException("PCM Hammer CLI or ECU image not found.");
            if (File.Exists(config.Backup) || File.Exists(config.Backup + ".verify.bin"))
                throw new IOException("Backup path already exists; an earlier backup must never be overwritten.");
            if (new FileInfo(config.Image).Length != 0x300000)
                throw new InvalidDataException("E39/E39A requires an exact 3 MiB complete image.");
            if (!HashMatches(config.Image, config.ExpectedImageSha256))
                throw new InvalidDataException("Image SHA-256 differs from the operator-approved image.");

            Console.WriteLine("Checking actual E39/E39A VIN and firmware identity...");
            await VerifyIdentityAsync(config);
            Console.WriteLine("Checking the original image using the external PCM Hammer checksum validator...");
            if (!await RunVisibleAsync(config, "--test-file-checksums", config.Image))
                throw new InvalidDataException("PCM Hammer rejected the image checksums.");

            // Fresh two-pass hardware backup before ANY destructive request.
            Console.WriteLine("Reading an original full backup from the physical ECU...");
            if (!await RunVisibleAsync(config, "--read", config.Backup, "--device", config.Device))
                throw new IOException("First hardware backup failed. Flashing aborted.");
            var second = config.Backup + ".verify.bin";
            Console.WriteLine("Reading the ECU independently a second time...");
            if (!await RunVisibleAsync(config, "--read", second, "--device", config.Device))
                throw new IOException("Second hardware backup failed. Flashing aborted.");
            if (new FileInfo(config.Backup).Length != 0x300000 ||
                new FileInfo(second).Length != 0x300000)
                throw new InvalidDataException("Incomplete E39/E39A hardware backup. Flashing aborted.");
            var firstDigest = SHA256.HashData(File.ReadAllBytes(config.Backup));
            var secondDigest = SHA256.HashData(File.ReadAllBytes(second));
            if (!CryptographicOperations.FixedTimeEquals(firstDigest, secondDigest))
                throw new InvalidDataException("Independent hardware reads disagree. Flashing aborted.");
            File.Delete(second);
            Console.WriteLine("Double readback matches; original backup SHA-256: " +
                Convert.ToHexString(firstDigest));

            // The adapter may be reconnected during the backup; never rely on
            // the identity read at startup for the ensuing destructive operation.
            await VerifyIdentityAsync(config);
            if (!HashMatches(config.Image, config.ExpectedImageSha256))
                throw new InvalidDataException("The proposed image changed after backup. Flashing aborted.");

            var confirmation = $"PROGRAM {config.Vin} {config.Mode.ToUpperInvariant()}";
            Console.WriteLine("A failed ECU flash can immobilize the vehicle.");
            Console.WriteLine("Use only a replacement ECU on a bench with stable power and a tested recovery method.");
            Console.WriteLine($"Type exactly: {confirmation}");
            if (!string.Equals(Console.ReadLine()?.Trim(), confirmation, StringComparison.Ordinal))
                throw new UnauthorizedAccessException("No explicit local operator authorization; no flash performed.");

            var operation = config.Mode == "full" ? "--write" : "--write-calibration";
            Console.WriteLine("Starting independent PCM Hammer physical programming. NEVER interrupt power.");
            if (!await RunVisibleAsync(config, operation, config.Image, "--device", config.Device))
                throw new IOException("Physical write failed or was interrupted. Preserve backup; do not blindly retry.");
            Console.WriteLine("Requesting an independent ECU verification through PCM Hammer...");
            if (!await RunVisibleAsync(config, "--verify", config.Image, "--device", config.Device))
                throw new IOException("ECU verification did not succeed; assess and preserve the original backup.");
            Console.WriteLine("PCM Hammer reports write and verification successful. Hardware qualification remains ECU-specific.");
            return 0;
        }
        catch (Exception error) when (error is ArgumentException or IOException or UnauthorizedAccessException or
            FileNotFoundException or InvalidOperationException or PlatformNotSupportedException)
        {
            Console.Error.WriteLine("PCM Hammer programming stopped: " + error.Message);
            return 8;
        }
    }
}
