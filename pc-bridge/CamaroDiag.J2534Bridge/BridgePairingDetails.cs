using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// The exact Android connection fields for a live TLS bridge session.
/// Never hard-code or persist the ephemeral token or certificate fingerprint.
/// </summary>
internal static class BridgePairingDetails
{
    internal static IReadOnlyList<string> PrivateIpv4Addresses()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                    n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Select(a => a.Address)
                .Where(a => a.AddressFamily == AddressFamily.InterNetwork &&
                    !IPAddress.IsLoopback(a) &&
                    BridgeServer.IsPrivateNetworkAddress(a))
                .Select(a => a.ToString()).Distinct()
                .OrderBy(a => a, StringComparer.Ordinal).ToArray();
        }
        catch (NetworkInformationException)
        {
            return [];
        }
    }

    internal static string FormatForAndroid(string host, int port, string pairingCode)
    {
        if (!IPAddress.TryParse(host, out var ip) ||
            ip.AddressFamily != AddressFamily.InterNetwork ||
            IPAddress.IsLoopback(ip) ||
            !BridgeServer.IsPrivateNetworkAddress(ip))
            throw new ArgumentException("Select a private LAN IPv4 address for Android.");
        if (port is < 1024 or > 65535)
            throw new ArgumentOutOfRangeException(nameof(port));
        if (string.IsNullOrWhiteSpace(pairingCode))
            throw new ArgumentException("Start the TLS bridge before copying the pairing fields.");
        var separator = pairingCode.LastIndexOf('.');
        if (separator <= 0 || separator == pairingCode.Length - 1 ||
            pairingCode.Length - separator - 1 != 64 ||
            pairingCode[(separator + 1)..].Any(c => !Uri.IsHexDigit(c)))
            throw new ArgumentException("The TLS pairing code must contain the certificate SHA-256 fingerprint.");

        return "CamaroDiag Android - pont Windows J2534" + Environment.NewLine +
               "Adresse IP : " + ip + Environment.NewLine +
               "Port : " + port + Environment.NewLine +
               "Code d'appairage sécurisé : " + pairingCode + Environment.NewLine +
               "Connexion : TLS obligatoire, PC et Android sur le même réseau privé." + Environment.NewLine +
               "Le code change à chaque redémarrage du pont.";
    }
}
