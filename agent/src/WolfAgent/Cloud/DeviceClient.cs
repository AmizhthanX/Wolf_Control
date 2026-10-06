using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Wolf.Agent.Config;
using Wolf.Agent.Security;

namespace Wolf.Agent.Cloud;

/// <summary>
/// Signed, outbound-only HTTP to the Supabase Edge Functions. Same scheme the
/// ESP32 uses (PROTOCOL section 3), so one server-side verifier serves both.
/// </summary>
public sealed class DeviceClient(AgentConfig config, ILogger<DeviceClient> log)
{
    // A separate, short connect timeout is what stops a boot-time attempt from
    // hanging. The service starts before the network stack is ready, and with
    // only an overall timeout a dead network costs the full 15 seconds before
    // it even reports failure — so the first successful report lands a minute
    // after boot instead of seconds. Five seconds is far longer than any real
    // connection needs and short enough to retry usefully.
    private readonly HttpClient _http = new(new SocketsHttpHandler
    {
        ConnectTimeout = TimeSpan.FromSeconds(5),
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
    })
    {
        Timeout = TimeSpan.FromSeconds(15),
    };

    public async Task<JsonDocument?> PostSignedAsync(
        string functionName, object payload, CancellationToken ct)
    {
        if (!config.IsProvisioned)
        {
            log.LogWarning("agent is not provisioned; skipping {Function}", functionName);
            return null;
        }

        var body = JsonSerializer.Serialize(payload);
        var path = $"/functions/v1/{functionName}";
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var nonce = WolfCrypto.RandomHex(16);

        var secret = config.Secret();
        string signature;
        try
        {
            signature = WolfCrypto.HmacSha256Hex(
                secret,
                WolfCrypto.DeviceSigningString(config.DeviceId, timestamp, nonce, "POST", path, body));
        }
        finally
        {
            Array.Clear(secret);
        }

        using var request = new HttpRequestMessage(HttpMethod.Post,
            config.SupabaseUrl.TrimEnd('/') + path)
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation("apikey", config.SupabaseAnonKey);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", config.SupabaseAnonKey);
        request.Headers.TryAddWithoutValidation("x-wolf-device", config.DeviceId);
        request.Headers.TryAddWithoutValidation("x-wolf-timestamp", timestamp.ToString());
        request.Headers.TryAddWithoutValidation("x-wolf-nonce", nonce);
        request.Headers.TryAddWithoutValidation("x-wolf-signature", signature);

        try
        {
            using var response = await _http.SendAsync(request, ct);
            var text = await response.Content.ReadAsStringAsync(ct);

            if (!response.IsSuccessStatusCode)
            {
                // The body is protocol-shaped JSON with no secrets in it
                // (PROTOCOL section 9), so logging it is safe and is usually
                // the fastest way to find a clock-skew or pairing problem.
                log.LogWarning("{Function} -> {Status} {Body}",
                    functionName, (int)response.StatusCode, Truncate(text, 400));
                return null;
            }

            return string.IsNullOrWhiteSpace(text) ? null : JsonDocument.Parse(text);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            // An offline agent is an expected state, not an incident: the PC
            // may simply be on a network that is down. Log and let the caller
            // back off.
            log.LogWarning("{Function} failed: {Message}", functionName, ex.Message);
            return null;
        }
    }

    private static string Truncate(string s, int max) =>
        s.Length <= max ? s : s[..max] + "...";
}
