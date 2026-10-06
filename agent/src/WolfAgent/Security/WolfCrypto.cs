using System.Security.Cryptography;
using System.Text;

namespace Wolf.Agent.Security;

/// <summary>
/// The agent's half of PROTOCOL sections 2 and 3. Every encoding here has to
/// match the Edge Functions and the ESP32 byte for byte.
/// </summary>
public static class WolfCrypto
{
    public static string Sha256Hex(string s) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(s))).ToLowerInvariant();

    public static string HmacSha256Hex(byte[] key, string message) =>
        Convert.ToHexString(HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(message)))
               .ToLowerInvariant();

    public static string RandomHex(int bytes) =>
        Convert.ToHexString(RandomNumberGenerator.GetBytes(bytes)).ToLowerInvariant();

    public static byte[] Base64UrlDecode(string s)
    {
        var padded = s.Replace('-', '+').Replace('_', '/');
        padded += (s.Length % 4) switch { 2 => "==", 3 => "=", 0 => "", _ => null! }
                  ?? throw new FormatException("bad base64url length");
        return Convert.FromBase64String(padded);
    }

    public static string Base64UrlEncode(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    /// <summary>
    /// Verify an ECDSA P-256 / SHA-256 signature in IEEE P1363 form against a
    /// base64url SPKI public key.
    /// </summary>
    public static bool VerifyEcdsaP256(string spkiBase64Url, string signatureBase64Url, string message)
    {
        try
        {
            var signature = Base64UrlDecode(signatureBase64Url);
            if (signature.Length != 64) return false;

            using var ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(Base64UrlDecode(spkiBase64Url), out _);

            return ecdsa.VerifyData(Encoding.UTF8.GetBytes(message), signature,
                                    HashAlgorithmName.SHA256,
                                    DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        }
        catch (Exception e) when (e is FormatException or CryptographicException or ArgumentException)
        {
            // Malformed input is a rejected request, not a crash: this runs on
            // a socket anything on the LAN can reach.
            return false;
        }
    }

    /// <summary>
    /// The device request signature, PROTOCOL section 3. Six lines: id,
    /// timestamp, nonce, method, function path, body hash.
    /// </summary>
    public static string DeviceSigningString(
        string deviceId, long unixSeconds, string nonce,
        string method, string functionPath, string body) =>
        string.Join('\n', deviceId, unixSeconds.ToString(), nonce,
                          method.ToUpperInvariant(), functionPath, Sha256Hex(body));
}
