using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Real loopback TLS 1.2+ handshake with an ephemeral, fingerprint-pinned
/// certificate. No J2534 adapter, ECU or external network access is needed.
/// </summary>
internal static class BridgeTlsSelfTests
{
    internal static int Run()
    {
        using var identity = BridgeTlsIdentity.Create();
        var certificateHash = SHA256.HashData(identity.Certificate.RawData);
        if (!string.Equals(Convert.ToHexString(certificateHash),
                identity.Fingerprint, StringComparison.Ordinal))
            throw new InvalidOperationException("TLS pairing fingerprint differs from certificate.");

        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var server = Task.Run(async () =>
        {
            using var accepted = await listener.AcceptTcpClientAsync();
            using var tls = new SslStream(accepted.GetStream(), leaveInnerStreamOpen: false);
            await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
            {
                ServerCertificate = identity.Certificate,
                EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                ClientCertificateRequired = false,
                AllowRenegotiation = false
            });
            var incoming = new byte[5];
            await tls.ReadExactlyAsync(incoming);
            if (!incoming.SequenceEqual(Encoding.ASCII.GetBytes("HELLO")))
                throw new InvalidOperationException("TLS channel corrupted the encrypted request.");
            await tls.WriteAsync(Encoding.ASCII.GetBytes("READY"));
        });

        using (var client = new TcpClient())
        {
            client.Connect(IPAddress.Loopback, port);
            using var tls = new SslStream(client.GetStream(), false,
                (_, certificate, _, _) => certificate != null &&
                    CryptographicOperations.FixedTimeEquals(
                        SHA256.HashData(certificate.GetRawCertData()),
                        certificateHash));
            tls.AuthenticateAsClient("CamaroDiag-J2534-Bridge");
            if (tls.SslProtocol is not (SslProtocols.Tls12 or SslProtocols.Tls13))
                throw new InvalidOperationException("The bridge TLS channel negotiated an insecure version.");
            tls.Write(Encoding.ASCII.GetBytes("HELLO"));
            tls.Flush();
            var reply = new byte[5];
            tls.ReadExactly(reply);
            if (!reply.SequenceEqual(Encoding.ASCII.GetBytes("READY")))
                throw new InvalidOperationException("TLS response was not authenticated.");
        }
        server.GetAwaiter().GetResult();
        return 3;
    }
}
