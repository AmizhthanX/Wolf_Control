using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text.Json;
using Wolf.Agent.Config;
using Wolf.Agent.Security;

namespace Wolf.Agent.Cloud;

/// <summary>Realtime carries an authenticated hint. device-poll still fetches and verifies commands.</summary>
public sealed class CommandWakeup(AgentConfig config, ILogger<CommandWakeup> log) : BackgroundService
{
    private readonly SemaphoreSlim _wake = new(0, 1);
    private readonly Dictionary<string, DateTimeOffset> _seen = new();
    private long _ref;
    public Task<bool> WaitAsync(TimeSpan timeout, CancellationToken ct) => _wake.WaitAsync(timeout, ct);
    public static string SigningString(string deviceId, string id, string expiry) =>
        string.Join('\n', "WOLFWAKE1", deviceId, id, expiry);

    public static bool Verify(byte[] secret, string deviceId, string id, string expiry,
        string signature, DateTimeOffset now)
    {
        if (!Guid.TryParse(id, out _) || !DateTimeOffset.TryParse(expiry, out var expires) ||
            expires <= now || expires > now.AddSeconds(90) || signature.Length != 64) return false;
        try
        {
            var expected = Convert.FromHexString(WolfCrypto.HmacSha256Hex(secret,
                SigningString(deviceId, id, expiry)));
            return CryptographicOperations.FixedTimeEquals(expected, Convert.FromHexString(signature));
        }
        catch (FormatException) { return false; }
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        if (!config.IsProvisioned) return;
        var retry = 2;
        while (!ct.IsCancellationRequested)
        {
            try { await ListenAsync(ct); retry = 2; }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (Exception ex) { log.LogWarning("command wake unavailable: {Type}", ex.GetType().Name); }
            await Task.Delay(TimeSpan.FromSeconds(retry), ct);
            retry = Math.Min(retry * 2, 60);
        }
    }

    private async Task ListenAsync(CancellationToken ct)
    {
        using var socket = new ClientWebSocket();
        var uri = new UriBuilder(config.SupabaseUrl)
        {
            Scheme = "wss", Port = -1, Path = "/realtime/v1/websocket",
            Query = $"apikey={Uri.EscapeDataString(config.SupabaseAnonKey)}&vsn=1.0.0",
        }.Uri;
        using (var connect = CancellationTokenSource.CreateLinkedTokenSource(ct))
        {
            connect.CancelAfter(TimeSpan.FromSeconds(10));
            await socket.ConnectAsync(uri, connect.Token);
        }
        var topic = "realtime:wolfpc-wake:" + WolfCrypto.Sha256Hex(config.DeviceId);
        await SendAsync(socket, topic, "phx_join", new
        {
            config = new { broadcast = new { ack = false, self = false }, @private = false },
            access_token = config.SupabaseAnonKey,
        }, ct);
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var heartbeat = HeartbeatAsync(socket, lifetime.Token);
        try
        {
            var buffer = new byte[4096];
            while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                using var frame = new MemoryStream();
                WebSocketReceiveResult result;
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeout.CancelAfter(TimeSpan.FromSeconds(50));
                do
                {
                    result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), timeout.Token);
                    if (result.MessageType == WebSocketMessageType.Close) return;
                    if (result.MessageType != WebSocketMessageType.Text || frame.Length + result.Count > 8192)
                        throw new InvalidDataException("invalid realtime frame");
                    frame.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);
                using var doc = JsonDocument.Parse(frame.ToArray());
                var root = doc.RootElement;
                var evt = root.GetProperty("event").GetString();
                if (evt is "phx_error" or "phx_close") throw new IOException("channel closed");
                if (evt == "phx_reply" && root.GetProperty("topic").GetString() == topic)
                {
                    if (root.GetProperty("payload").GetProperty("status").GetString() != "ok")
                        throw new IOException("channel rejected");
                    log.LogInformation("command wake channel connected");
                }
                if (evt != "broadcast" || root.GetProperty("topic").GetString() != topic) continue;
                var payload = root.GetProperty("payload");
                if (payload.GetProperty("event").GetString() == "wake")
                    Handle(payload.GetProperty("payload"));
            }
        }
        finally
        {
            lifetime.Cancel();
            socket.Abort();
            try { await heartbeat; } catch (Exception) when (lifetime.IsCancellationRequested) { }
        }
    }

    private void Handle(JsonElement payload)
    {
        try
        {
            var id = payload.GetProperty("command_id").GetString() ?? "";
            var expiry = payload.GetProperty("expires_at").GetString() ?? "";
            var signature = payload.GetProperty("signature").GetString() ?? "";
            var secret = config.Secret();
            bool valid;
            try { valid = Verify(secret, config.DeviceId, id, expiry, signature, DateTimeOffset.UtcNow); }
            finally { CryptographicOperations.ZeroMemory(secret); }
            if (!valid) return;
            foreach (var old in _seen.Where(p => p.Value < DateTimeOffset.UtcNow).Select(p => p.Key).ToArray())
                _seen.Remove(old);
            if (_seen.Count >= 128 || !_seen.TryAdd(id, DateTimeOffset.Parse(expiry))) return;
            if (_wake.CurrentCount == 0) _wake.Release();
            log.LogInformation("signed command wake received ({Id})", id);
        }
        catch (Exception ex) when (ex is KeyNotFoundException or InvalidOperationException or FormatException) { }
    }

    private async Task HeartbeatAsync(ClientWebSocket socket, CancellationToken ct)
    {
        try
        {
            while (true)
            {
                await Task.Delay(TimeSpan.FromSeconds(20), ct);
                await SendAsync(socket, "phoenix", "heartbeat", new { }, ct);
            }
        }
        catch { socket.Abort(); throw; }
    }

    private Task SendAsync(ClientWebSocket socket, string topic, string evt, object payload, CancellationToken ct)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(new
        {
            topic, @event = evt, payload, @ref = Interlocked.Increment(ref _ref).ToString(),
        });
        return socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, ct);
    }
}
