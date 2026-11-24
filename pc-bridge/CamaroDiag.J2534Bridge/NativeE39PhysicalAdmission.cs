using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Local J2534 bench admission checks the DLL path, channel and architecture.
/// Driver SHA-256 pinning is optional, never an OEM key or a license.
/// </summary>
internal static class NativeE39PhysicalAdmission
{
    internal sealed record QualifiedChannel(string DriverSha256, string RegistryView,
        int RequestCanId, int ResponseCanId, int ProtocolNumber,
        int MaximumStockReadBytes, int StockReadSession);

    // Keep the verified file open without write/delete sharing during the
    // dedicated Windows programming operation, instead of checking a path
    // and immediately releasing it before loading the vendor DLL.
    internal static FileStream OpenVerifiedDriverLease(J2534Adapter adapter, J2534MemoryReadScope scope,
        QualifiedChannel channel)
    {
        ArgumentNullException.ThrowIfNull(adapter);
        ArgumentNullException.ThrowIfNull(scope);
        ArgumentNullException.ThrowIfNull(channel);

        if (channel.ProtocolNumber is not (33 or 34) ||
            !scope.MatchesChannel(channel.RequestCanId, channel.ResponseCanId, channel.ProtocolNumber) ||
            scope.MaxReadBytes > channel.MaximumStockReadBytes ||
            scope.Session != channel.StockReadSession ||
            !string.Equals(adapter.RegistryView, channel.RegistryView, StringComparison.Ordinal))
            throw new UnauthorizedAccessException("J2534 driver architecture or CAN read channel differs from qualified setup.");

        // Remote shares and Windows device/extended paths cannot provide the
        // same local-file lease guarantees as an installed vendor DLL.
        if (!Path.IsPathFullyQualified(adapter.FunctionLibrary) ||
            adapter.FunctionLibrary.StartsWith(@"\\", StringComparison.Ordinal))
            throw new UnauthorizedAccessException("Only a local, explicitly identified installed J2534 DLL is allowed.");
        var stream = new FileStream(adapter.FunctionLibrary, FileMode.Open,
            FileAccess.Read, FileShare.Read);
        try
        {
            var digest = Convert.ToHexString(SHA256.HashData(stream));
            if (channel.DriverSha256.Length != 0 &&
                !string.Equals(digest, channel.DriverSha256, StringComparison.Ordinal))
                throw new UnauthorizedAccessException("Installed J2534 DLL differs from the independently qualified driver version.");
            return stream;
        }
        catch
        {
            stream.Dispose();
            throw;
        }
    }
}
