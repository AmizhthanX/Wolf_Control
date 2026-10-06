using Microsoft.Extensions.Logging;
using Wolf.Agent.Cloud;
using Wolf.Agent.Config;
using Wolf.Agent.Security;

static void Check(bool ok, string name)
{
    if (!ok) throw new Exception("FAIL: " + name);
    Console.WriteLine("PASS: " + name);
}

var secret = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
var now = DateTimeOffset.UtcNow;
var id = Guid.NewGuid().ToString();
var expiry = now.AddSeconds(60).ToString("O");
var signature = WolfCrypto.HmacSha256Hex(secret, CommandWakeup.SigningString("test-agent", id, expiry));
Check(CommandWakeup.Verify(secret, "test-agent", id, expiry, signature, now), "valid wake");
Check(!CommandWakeup.Verify(secret, "other-agent", id, expiry, signature, now), "wrong target rejected");
Check(!CommandWakeup.Verify(secret, "test-agent", Guid.NewGuid().ToString(), expiry, signature, now), "tampered command rejected");
Check(!CommandWakeup.Verify(secret, "test-agent", id, expiry, signature, now.AddSeconds(61)), "expired wake rejected");
Check(!CommandWakeup.Verify(secret, "test-agent", id, expiry, new string('z', 64), now), "malformed signature rejected");

using var key = System.Security.Cryptography.ECDsa.Create(System.Security.Cryptography.ECCurve.NamedCurves.nistP256);
var publicKey = WolfCrypto.Base64UrlEncode(key.ExportSubjectPublicKeyInfo());
var envelope = new Envelope
{
    CommandId = id, DeviceId = "test-pc", Command = "LOCK",
    Timestamp = now.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'"),
    ExpiresAt = now.AddSeconds(60).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'"),
    Nonce = "0123456789abcdef",
};
envelope.Signature = WolfCrypto.Base64UrlEncode(key.SignData(
    System.Text.Encoding.UTF8.GetBytes(envelope.SigningString()),
    System.Security.Cryptography.HashAlgorithmName.SHA256,
    System.Security.Cryptography.DSASignatureFormat.IeeeP1363FixedFieldConcatenation));
var verifier = new EnvelopeVerifier();
verifier.SetTrustedKeys([publicKey]);
Check(verifier.Verify(envelope, "test-pc") == EnvelopeCheck.Ok, "signed LOCK accepted");
envelope.Command = "SHUTDOWN";
Check(verifier.Verify(envelope, "test-pc") == EnvelopeCheck.BadSignature, "command substitution rejected");
envelope.Command = "LOCK";
verifier.Remember(id);
Check(verifier.Verify(envelope, "test-pc") == EnvelopeCheck.Replay, "LOCK replay rejected");

if (args.Contains("--live-wake"))
{
    // Listen only: this harness never fetches or executes any command.
    var config = AgentConfig.Load();
    Check(config.IsProvisioned, "local agent configuration readable");
    using var factory = LoggerFactory.Create(b => b.AddSimpleConsole());
    using var wake = new CommandWakeup(config, factory.CreateLogger<CommandWakeup>());
    await wake.StartAsync(CancellationToken.None);
    Console.WriteLine("Waiting up to 45 seconds for live signed wake...");
    Check(await wake.WaitAsync(TimeSpan.FromSeconds(45), CancellationToken.None), "live realtime wake delivered");
    Check(!await wake.WaitAsync(TimeSpan.FromSeconds(3), CancellationToken.None), "duplicate wake ignored");
    await wake.StopAsync(CancellationToken.None);
}
