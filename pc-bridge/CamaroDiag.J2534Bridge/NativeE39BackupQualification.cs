using System.Globalization;
using System.Security.Cryptography;
using System.Runtime.InteropServices;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Local physical backup qualification for future E39 programming.
/// It never opens a programming session or sends erase / write commands.
/// Only explicitly scoped UDS memory reads are supported here; stock GMLAN
/// variants without the required F190/23 services will fail qualification.
/// </summary>
internal static class NativeE39BackupQualification
{
    internal const string Command = "--qualify-e39-backup";

    internal sealed record Evidence(string Vin, uint OsId, string Sha256, int Bytes);

    internal static void RequireFullScope(J2534MemoryReadScope scope)
    {
        if (scope.StartAddress != 0 || scope.Length != NativeE39ImageInspector.ImageBytes)
            throw new ArgumentException("Qualification requires a documented complete 3 MiB E39 flash read scope.");
    }

    internal static Evidence Compare(
        J2534MemoryReadScope scope, byte[] original,
        NativeScopedMemoryReader.VerifiedCapture captured,
        NativeGmlanIdentification.Identity first,
        NativeGmlanIdentification.Identity second)
    {
        RequireFullScope(scope);
        var identity = NativeGmlanBenchProbe.RequireStableIdentity(first, second);
        if (identity.Vin != scope.ExpectedVin || captured.ExpectedVin != scope.ExpectedVin ||
            captured.StartAddress != scope.StartAddress)
            throw new UnauthorizedAccessException("Backup capture, VIN and live ECU identity disagree.");

        var inspection = NativeE39ImageInspector.Inspect(original);
        if (inspection.Status != NativeE39ImageInspector.Verdict.Valid)
            throw new InvalidDataException("Original image is not a checksum-valid E39 image.");
        if (inspection.OsIdentifier != identity.OperatingSystemId)
            throw new UnauthorizedAccessException("Original image OSID differs from live ECU.");

        var expectedHash = Convert.ToHexString(SHA256.HashData(original));
        if (captured.SecondBytes is null ||
            ReferenceEquals(captured.Bytes, captured.SecondBytes) ||
            !CryptographicOperations.FixedTimeEquals(captured.Bytes, captured.SecondBytes) ||
            captured.Bytes.Length != NativeE39ImageInspector.ImageBytes ||
            captured.BlocksPerPass != (scope.Length + (uint)scope.MaxReadBytes - 1) /
                (uint)scope.MaxReadBytes ||
            captured.Sha256 != expectedHash ||
            !CryptographicOperations.FixedTimeEquals(original, captured.Bytes))
            throw new InvalidDataException("Two-pass physical capture differs from the original full flash image.");

        return new Evidence(identity.Vin, identity.OperatingSystemId, expectedHash, original.Length);
    }

    private sealed class ScopedLink(J2534Device device) : NativeScopedMemoryReader.ITransport
    {
        public Task<byte[]> ExchangeAsync(byte[] request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            var result = device.Exchange(request, 6000);
            token.ThrowIfCancellationRequested();
            return Task.FromResult(result);
        }
    }

    internal static async Task<int> RunAsync(string[] args)
    {
        if (args.Length != 4 || !args[0].Equals(Command, StringComparison.OrdinalIgnoreCase) ||
            !int.TryParse(args[1], NumberStyles.None, CultureInfo.InvariantCulture, out var index))
        {
            Console.Error.WriteLine("Usage: CamaroDiag.J2534Bridge.exe " + Command +
                " <adapter-index> <documented-read-profile.json> <original-3MiB.bin>");
            return 10;
        }

        using var cancel = new CancellationTokenSource();
        ConsoleCancelEventHandler onCancel = (_, e) => { e.Cancel = true; cancel.Cancel(); };
        Console.CancelKeyPress += onCancel;
        try
        {
            var scope = J2534MemoryReadScope.Load(args[2]);
            RequireFullScope(scope);
            var path = Path.GetFullPath(args[3]);
            if (new FileInfo(path).Length != NativeE39ImageInspector.ImageBytes)
                throw new InvalidDataException("Original file must contain exactly 3 MiB.");
            var original = File.ReadAllBytes(path);
            if (NativeE39ImageInspector.Inspect(original).Status != NativeE39ImageInspector.Verdict.Valid)
                throw new InvalidDataException("Original file checksum or layout validation failed.");

            var adapters = J2534Registry.Discover();
            if (index < 0 || index >= adapters.Count)
                throw new ArgumentException("An installed J2534 adapter must be selected explicitly.");
            using var device = new J2534Device(adapters[index], scope);
            device.Open(scope.RequestCanId, scope.ResponseCanId,
                scope.ProtocolNumber is 34 or 36 or 64, scope.ProtocolNumber);
            var link = new ScopedLink(device);
            var first = device.ReadGmlanIdentification();
            if (first.Vin != scope.ExpectedVin ||
                NativeE39ImageInspector.Inspect(original).OsIdentifier != first.OperatingSystemId)
                throw new UnauthorizedAccessException("Live VIN or OSID differs from the original image/profile.");
            Console.WriteLine("Acquiring full flash twice via the explicitly selected J2534 read scope.");
            var capture = await NativeScopedMemoryReader.ReadWithSessionAsync(link, scope, null, cancel.Token);
            cancel.Token.ThrowIfCancellationRequested();
            var second = device.ReadGmlanIdentification();
            var evidence = Compare(scope, original, capture, first, second);
            Console.WriteLine("Physical backup comparison succeeded (READ ONLY).");
            Console.WriteLine($"VIN: {evidence.Vin}; OSID: {evidence.OsId}; Bytes: {evidence.Bytes}");
            Console.WriteLine("SHA-256: " + evidence.Sha256);
            Console.WriteLine("This does not qualify an erase map, flash kernel, or recovery procedure.");
            return 0;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            ArgumentException or TimeoutException or InvalidOperationException or
            DllNotFoundException or BadImageFormatException or EntryPointNotFoundException or
            ExternalException or OperationCanceledException or System.Text.Json.JsonException or
            KeyNotFoundException or OverflowException)
        {
            Console.Error.WriteLine("Physical backup qualification failed: " + error.Message);
            return 12;
        }
        finally { Console.CancelKeyPress -= onCancel; }
    }
}
