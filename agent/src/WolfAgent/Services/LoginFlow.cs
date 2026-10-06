using System.Collections.Concurrent;
using System.Text.Json;
using Wolf.Agent.Cloud;
using Wolf.Agent.Config;
using Wolf.Agent.Security;

namespace Wolf.Agent.Services;

public enum LoginWait { Waiting, Approved, Denied, Expired, Error }

public readonly record struct LoginWaitResult(LoginWait State, string? Token, string? Error);

/// <summary>
/// The networked half of Phase 8 (PRD section 15). The credential provider runs
/// inside LogonUI, which cannot reach the network and has no business holding
/// crypto material, so everything that talks to Supabase or verifies a
/// signature happens here and the two halves meet over a named pipe.
///
/// The rule that shapes this class: <em>the cloud relays the answer, it does not
/// get to assert that the answer was valid.</em> login-respond verifies the
/// phone's signature server-side, and then this code verifies it again against
/// a key pinned at pairing. A compromised control plane can delay a login or
/// refuse one; it cannot manufacture an approval.
/// </summary>
public sealed class LoginFlow(
    AgentConfig config,
    DeviceClient client,
    EnvelopeVerifier verifier,
    ILogger<LoginFlow> log)
{
    /// <summary>
    /// A one-shot right to the sealed credential, handed out once an approval
    /// has verified. Both entry points - the phone sending a LOGIN command, and
    /// the provider asking for a challenge - converge on one of these, so there
    /// is a single place where the credential can leave.
    /// </summary>
    private sealed record Grant(DateTimeOffset ExpiresAt);

    private readonly ConcurrentDictionary<string, Grant> _grants = new(StringComparer.Ordinal);

    // Long enough to cover a slow LogonUI, short enough that a token left
    // behind by an abandoned attempt is useless by the time anyone finds it.
    private static readonly TimeSpan GrantTtl = TimeSpan.FromSeconds(60);

    private sealed record Poll(DateTimeOffset At, LoginWaitResult Result);
    private readonly ConcurrentDictionary<string, Poll> _lastPoll = new(StringComparer.Ordinal);

    // The provider polls in a loop while the user is looking at a spinner. Each
    // server poll is an Edge Function invocation, and the free tier is the
    // reason the rest of this system runs on 30 second timers (docs/DEPLOY.md),
    // so a login attempt is capped at roughly one call every two seconds -
    // about 30 for a full 60 second window, a few times a day. Answering from
    // this cache in between costs nothing and looks identical to the provider.
    private static readonly TimeSpan PollFloor = TimeSpan.FromSeconds(2);

    /// <summary>
    /// Mint a grant for an approval that has already been proven some other
    /// way. The phone-initiated path uses this: a LOGIN command arrives as a
    /// signed envelope and <see cref="EnvelopeVerifier"/> has already checked
    /// the signature, the expiry and the replay window, so re-challenging the
    /// phone would only ask it to prove the same thing twice.
    /// </summary>
    public string MintGrant()
    {
        var token = WolfCrypto.RandomHex(16);
        _grants[token] = new Grant(DateTimeOffset.UtcNow + GrantTtl);
        Sweep();
        return token;
    }

    /// <summary>
    /// Ask the server for a fresh challenge. Returns its id, or an error the
    /// provider can put on screen.
    /// </summary>
    public async Task<(string? ChallengeId, string? Error)> CreateChallengeAsync(CancellationToken ct)
    {
        // Fail here rather than after the user has picked up their phone and
        // touched a sensor. Nothing downstream can succeed without this.
        if (!CredentialVault.Exists)
            return (null, "no credential enrolled on this PC; run WolfAgent.exe enroll-login");

        using var response = await client.PostSignedAsync(
            "login-challenge", new { action = "create" }, ct);

        if (response is null) return (null, "could not reach the server");

        if (!response.RootElement.TryGetProperty("challenge_id", out var id) ||
            id.ValueKind != JsonValueKind.String)
            return (null, "server did not return a challenge");

        log.LogInformation("login challenge created");
        return (id.GetString(), null);
    }

    /// <summary>One poll of an open challenge, rate limited and cached.</summary>
    public async Task<LoginWaitResult> AwaitAsync(string challengeId, CancellationToken ct)
    {
        if (_lastPoll.TryGetValue(challengeId, out var cached) &&
            DateTimeOffset.UtcNow - cached.At < PollFloor)
            return cached.Result;

        var result = await PollOnceAsync(challengeId, ct);
        _lastPoll[challengeId] = new Poll(DateTimeOffset.UtcNow, result);
        return result;
    }

    private async Task<LoginWaitResult> PollOnceAsync(string challengeId, CancellationToken ct)
    {
        using var response = await client.PostSignedAsync(
            "login-challenge", new { action = "poll", challenge_id = challengeId }, ct);

        if (response is null)
            return new LoginWaitResult(LoginWait.Error, null, "could not reach the server");

        var root = response.RootElement;
        var result = Text(root, "result");

        switch (result)
        {
            case "PENDING":
                return new LoginWaitResult(LoginWait.Waiting, null, null);
            case "DENIED":
                return new LoginWaitResult(LoginWait.Denied, null, null);
            case "EXPIRED":
                return new LoginWaitResult(LoginWait.Expired, null, null);
            case "APPROVED":
                break;
            default:
                return new LoginWaitResult(LoginWait.Error, null, "unexpected challenge state");
        }

        var signature = Text(root, "signature");
        var publicKey = Text(root, "public_key");
        var challenge = Text(root, "challenge");

        if (signature is null || publicKey is null || challenge is null)
            return new LoginWaitResult(LoginWait.Error, null, "approval was incomplete");

        // The key has to be one we already pinned. Without this check the
        // server could answer with a key of its own choosing and a matching
        // signature, and every verification below would pass.
        if (!verifier.IsTrusted(publicKey))
        {
            log.LogWarning("login approved by a key this PC does not trust; refusing");
            return new LoginWaitResult(LoginWait.Error, null, "approved by an unknown phone");
        }

        // A distinct prefix from the command envelope's WOLFPC1, so neither can
        // ever be replayed as the other (docs/PHASE8-LOGIN.md).
        var message = string.Join('\n', "WOLFLOGIN1", challengeId, config.PcId, challenge);

        if (!WolfCrypto.VerifyEcdsaP256(publicKey, signature, message))
        {
            log.LogWarning("login signature did not verify locally; refusing");
            return new LoginWaitResult(LoginWait.Error, null, "signature did not verify");
        }

        log.LogInformation("login approved and verified locally");
        return new LoginWaitResult(LoginWait.Approved, MintGrant(), null);
    }

    public async Task CancelAsync(string challengeId, CancellationToken ct)
    {
        _lastPoll.TryRemove(challengeId, out _);
        await client.PostSignedAsync(
            "login-challenge", new { action = "cancel", challenge_id = challengeId }, ct);
    }

    /// <summary>
    /// Redeem a grant for the sealed credential. One shot: the token is removed
    /// before the vault is opened, so a repeated CLAIM - whether from a retry or
    /// from anything else that got hold of the token - finds nothing.
    /// </summary>
    public LogonCredential? Redeem(string token)
    {
        if (!_grants.TryRemove(token, out var grant)) return null;
        if (grant.ExpiresAt < DateTimeOffset.UtcNow) return null;
        return CredentialVault.Unseal();
    }

    private void Sweep()
    {
        var now = DateTimeOffset.UtcNow;
        foreach (var (token, grant) in _grants)
            if (grant.ExpiresAt < now) _grants.TryRemove(token, out _);
        foreach (var (id, poll) in _lastPoll)
            if (now - poll.At > TimeSpan.FromMinutes(5)) _lastPoll.TryRemove(id, out _);
    }

    private static string? Text(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
}
