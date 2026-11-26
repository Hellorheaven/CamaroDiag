using System.Security.Cryptography;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Dedicated E39 bench writer admission for the physical J2534 driver.
/// The operating system owns the open file lock for the *entire* asynchronous
/// read, programming and journal flush. A separate process using CamaroDiag
/// cannot open the same vendor DLL for another destructive bench session.
/// Different installed driver paths remain independent.
///
/// Standard J2534 discovery does not identify which of several devices behind
/// one vendor DLL is connected. Serialize these sessions conservatively; this
/// is a concurrency guard, not a programming entitlement or transport ban.
/// External third-party tools cannot be required to honor this lock.
/// </summary>
internal static class NativeE39PhysicalBenchGate
{
    internal static IDisposable Acquire(J2534Adapter adapter)
    {
        ArgumentNullException.ThrowIfNull(adapter);
        if (!Path.IsPathFullyQualified(adapter.FunctionLibrary) ||
            adapter.FunctionLibrary.StartsWith(@"\\", StringComparison.Ordinal))
            throw new UnauthorizedAccessException("A local installed J2534 driver path is required.");

        var path = Path.GetFullPath(adapter.FunctionLibrary);
        var identity = OperatingSystem.IsWindows() ? path.ToUpperInvariant() : path;
        var key = identity + "|" + adapter.RegistryView.ToUpperInvariant();
        var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(key)));
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "CamaroDiag", "exclusive-bench");
        Directory.CreateDirectory(root);
        var lockPath = Path.Combine(root, digest + ".lock");
        try
        {
            // FileShare.None uses an OS-backed share reservation, unlike a
            // SemaphoreSlim local to one transport wrapper or one process.
            // Closing the handle releases the reservation, even after a crash.
            return new FileStream(lockPath, FileMode.OpenOrCreate,
                FileAccess.ReadWrite, FileShare.None, 1, FileOptions.None);
        }
        catch (IOException error)
        {
            throw new InvalidOperationException(
                "Another local E39 programming operation already owns this J2534 driver.",
                error);
        }
    }
}
