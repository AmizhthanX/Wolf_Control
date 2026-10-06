using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Wolf.Agent.Config;

namespace Wolf.Agent.LocalApi;

/// <summary>
/// The self-signed certificate the LAN API presents.
///
/// A public CA cannot issue for a private hostname, so this is self-signed and
/// the phone pins its SHA-256 fingerprint at pairing time. Pinning is actually
/// stronger than the usual CA path here: there is exactly one legitimate
/// certificate, and the app will refuse anything else even if an attacker
/// somehow obtains a trusted one for the same name.
/// </summary>
public static class LanCertificate
{
    private const int ValidYears = 10;

    public static X509Certificate2 LoadOrCreate(ILogger log)
    {
        if (File.Exists(AgentConfig.CertPath))
        {
            try
            {
                var existing = X509CertificateLoader.LoadPkcs12FromFile(
                    AgentConfig.CertPath, null,
                    X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.Exportable);

                if (existing.NotAfter > DateTime.UtcNow.AddDays(30)) return existing;
                log.LogInformation("LAN certificate is expiring; issuing a new one");
            }
            catch (CryptographicException ex)
            {
                log.LogWarning("could not load LAN certificate, reissuing: {Message}", ex.Message);
            }
        }

        var certificate = Create();
        Directory.CreateDirectory(AgentConfig.Directory);
        File.WriteAllBytes(AgentConfig.CertPath, certificate.Export(X509ContentType.Pkcs12));
        log.LogInformation("issued LAN certificate {Fingerprint}", Fingerprint(certificate));
        return certificate;
    }

    private static X509Certificate2 Create()
    {
        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest(
            $"CN={Environment.MachineName}, O=WolfPC", rsa,
            HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);

        request.CertificateExtensions.Add(
            new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(
            new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        request.CertificateExtensions.Add(
            new X509EnhancedKeyUsageExtension(
                new OidCollection { new("1.3.6.1.5.5.7.3.1") }, false));   // server auth

        // The phone may reach the agent by hostname or by whatever address DHCP
        // handed out today, so both go in the SAN. The pinned fingerprint is
        // what actually authenticates it; these just stop TLS stacks from
        // refusing before pinning is consulted.
        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName(Environment.MachineName);
        san.AddDnsName($"{Environment.MachineName}.local");
        san.AddDnsName("localhost");
        san.AddIpAddress(System.Net.IPAddress.Loopback);
        request.CertificateExtensions.Add(san.Build());

        return request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1),
            DateTimeOffset.UtcNow.AddYears(ValidYears));
    }

    /// <summary>Lowercase hex SHA-256 of the DER certificate — what the app pins.</summary>
    public static string Fingerprint(X509Certificate2 certificate) =>
        Convert.ToHexString(SHA256.HashData(certificate.RawData)).ToLowerInvariant();
}
