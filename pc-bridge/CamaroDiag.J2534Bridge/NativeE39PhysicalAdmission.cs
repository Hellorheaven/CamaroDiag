using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// A bench qualification applies to an exact installed J2534 DLL and CAN
/// channel, not every adapter which advertises the PassThru API. No qualified
/// channel records are bundled with the application.
/// </summary>
internal static class NativeE39PhysicalAdmission
{
    internal sealed record QualifiedChannel(string DriverSha256, string RegistryView,
        int RequestCanId, int ResponseCanId, int ProtocolNumber,
        int MaximumStockReadBytes, int StockReadSession);

    internal static void RequireMatch(J2534Adapter adapter, J2534MemoryReadScope scope,
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

        if (!Path.IsPathFullyQualified(adapter.FunctionLibrary))
            throw new UnauthorizedAccessException("Only an explicitly identified installed J2534 DLL is allowed.");
        using var stream = new FileStream(adapter.FunctionLibrary, FileMode.Open,
            FileAccess.Read, FileShare.Read);
        var digest = Convert.ToHexString(SHA256.HashData(stream));
        if (!string.Equals(digest, channel.DriverSha256, StringComparison.Ordinal))
            throw new UnauthorizedAccessException("Installed J2534 DLL differs from the independently qualified driver version.");
    }
}
