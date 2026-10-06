using System.Collections.Concurrent;
using System.Globalization;
using System.Text.Json.Serialization;

namespace Wolf.Agent.Security;

public sealed class Envelope
{
    [JsonPropertyName("command_id")] public string CommandId { get; set; } = "";
    [JsonPropertyName("device_id")]  public string DeviceId  { get; set; } = "";
    [JsonPropertyName("command")]    public string Command   { get; set; } = "";
    [JsonPropertyName("timestamp")]  public string Timestamp { get; set; } = "";
    [JsonPropertyName("expires_at")] public string ExpiresAt { get; set; } = "";
    [JsonPropertyName("nonce")]      public string Nonce     { get; set; } = "";
    [JsonPropertyName("signature")]  public string Signature { get; set; } = "";
    [JsonPropertyName("public_key")] public string? PublicKey { get; set; }

    /// <summary>PROTOCOL section 2. Seven lines, single newlines, no trailing newline.</summary>
    public string SigningString() =>
        string.Join('\n', "WOLFPC1", CommandId, DeviceId, Command, Timestamp, ExpiresAt, Nonce);
}

public enum EnvelopeCheck
{
    Ok, Malformed, UnknownCommand, Expired, Replay, BadSignature, NoTrustedKey
}

/// <summary>
/// Verification shared by the cloud path and the LAN path.
///
/// The agent verifies signatures itself rather than trusting that the Edge
/// Function already did. That is what makes a compromised control plane
/// insufficient to shut the machine down: Supabase relays envelopes, it cannot
/// author them.
/// </summary>
public sealed class EnvelopeVerifier
{
    // The agent's three commands. A POWER_ON arriving here would be a routing
    // bug; the controller owns that one.
    private static readonly HashSet<string> Accepted = new(StringComparer.Ordinal)
        { "SHUTDOWN", "RESTART", "LOGIN", "LOCK" };

    private readonly ConcurrentDictionary<string, DateTimeOffset> _seen = new();
    private readonly List<string> _trustedKeys = new();
    private readonly Lock _gate = new();

    public void SetTrustedKeys(IEnumerable<string> keys)
    {
        lock (_gate)
        {
            _trustedKeys.Clear();
            _trustedKeys.AddRange(keys.Where(k => !string.IsNullOrWhiteSpace(k)));
        }
    }

    public int TrustedKeyCount { get { lock (_gate) return _trustedKeys.Count; } }

    /// <summary>
    /// Whether this is a key the PC already pinned. Phase 8 needs it: a login
    /// approval arrives as a signature plus the key that made it, and without
    /// this check the server could supply both and pass its own verification.
    /// </summary>
    public bool IsTrusted(string publicKey)
    {
        if (string.IsNullOrWhiteSpace(publicKey)) return false;
        lock (_gate) return _trustedKeys.Contains(publicKey);
    }

    public EnvelopeCheck Verify(Envelope e, string expectedPcId)
    {
        if (string.IsNullOrEmpty(e.CommandId) || string.IsNullOrEmpty(e.Signature)
            || e.Nonce.Length != 16)
            return EnvelopeCheck.Malformed;

        if (!Accepted.Contains(e.Command)) return EnvelopeCheck.UnknownCommand;
        if (!string.Equals(e.DeviceId, expectedPcId, StringComparison.Ordinal))
            return EnvelopeCheck.Malformed;

        if (!TryParseUtc(e.Timestamp, out _) || !TryParseUtc(e.ExpiresAt, out var expires))
            return EnvelopeCheck.Malformed;
        if (expires < DateTimeOffset.UtcNow) return EnvelopeCheck.Expired;

        PruneSeen();
        if (_seen.ContainsKey(e.CommandId)) return EnvelopeCheck.Replay;

        string[] keys;
        lock (_gate) keys = _trustedKeys.ToArray();
        if (keys.Length == 0) return EnvelopeCheck.NoTrustedKey;

        var message = e.SigningString();
        foreach (var key in keys)
        {
            // A key attached to the envelope is a hint, not an authority: it
            // still has to be one we already trust.
            if (!string.IsNullOrEmpty(e.PublicKey) && e.PublicKey != key) continue;
            if (WolfCrypto.VerifyEcdsaP256(key, e.Signature, message)) return EnvelopeCheck.Ok;
        }
        return EnvelopeCheck.BadSignature;
    }

    public void Remember(string commandId) => _seen[commandId] = DateTimeOffset.UtcNow;

    /// <summary>
    /// Entries only need to outlive the longest command window (60 s). Holding
    /// them for ten minutes is cheap and covers clock wobble.
    /// </summary>
    private void PruneSeen()
    {
        var cutoff = DateTimeOffset.UtcNow.AddMinutes(-10);
        foreach (var (id, at) in _seen)
            if (at < cutoff) _seen.TryRemove(id, out _);
    }

    private static bool TryParseUtc(string s, out DateTimeOffset value) =>
        DateTimeOffset.TryParseExact(s, "yyyy-MM-ddTHH:mm:ssZ",
            CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out value);
}
