using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Fresh TLS identity for each bridge process. Android pins the SHA-256
/// certificate fingerprint from the local pairing code. On Windows, Schannel
/// requires a non-ephemeral private key handle, so the generated certificate
/// is imported with a temporary, user-scoped key (no PersistKeySet). No PFX
/// file or trusted CA entry is created; disposal releases the temporary key.
/// TLS must complete before any pairing token is sent.
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
        using var generated = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddMinutes(-5),
            DateTimeOffset.UtcNow.AddDays(1));
        // Windows Schannel cannot reliably use a purely ephemeral private
        // key from CertificateRequest.CreateSelfSigned. Export only to RAM,
        // then import into the current user's *temporary* key storage.
        // Do not use PersistKeySet or MachineKeySet (no admin required).
        var password = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
        var pkcs12 = generated.Export(X509ContentType.Pkcs12, password);
        try
        {
            var usable = new X509Certificate2(
                pkcs12, password, X509KeyStorageFlags.UserKeySet);
            if (!usable.HasPrivateKey)
            {
                usable.Dispose();
                throw new CryptographicException("TLS identity is missing its private key.");
            }
            return new BridgeTlsIdentity(usable);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pkcs12);
        }
    }

    public void Dispose() => Certificate.Dispose();
}
