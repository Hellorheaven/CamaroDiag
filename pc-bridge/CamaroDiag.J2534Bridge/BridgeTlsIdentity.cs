using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Fresh ephemeral identity for each bridge process. The authenticated Android
/// client receives the SHA-256 certificate fingerprint out of band in the
/// operator-displayed pairing code. Neither certificates nor private keys are
/// written to disk; TLS is mandatory before the first pairing token is sent.
/// </summary>
internal sealed class BridgeTlsIdentity : IDisposable
{
    private BridgeTlsIdentity(X509Certificate2 certificate)
    {
        Certificate = certificate;
        Fingerprint = Convert.ToHexString(SHA256.HashData(certificate.RawData));
    }

    public X509Certificate2 Certificate { get; }
    public string Fingerprint { get; }

    public static BridgeTlsIdentity Create()
    {
        using var rsa = RSA.Create(3072);
        var request = new CertificateRequest(
            "CN=CamaroDiag-J2534-Bridge", rsa, HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, true));
        // Small clock skew tolerated. The certificate changes at each bridge
        // restart, so the operator must pair with a fresh displayed code.
        var certificate = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddMinutes(-5),
            DateTimeOffset.UtcNow.AddDays(1));
        return new BridgeTlsIdentity(certificate);
    }

    public void Dispose() => Certificate.Dispose();
}
