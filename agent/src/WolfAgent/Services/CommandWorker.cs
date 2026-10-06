using System.Text.Json;
using Wolf.Agent.Cloud;
using Wolf.Agent.Config;
using Wolf.Agent.Security;

namespace Wolf.Agent.Services;

/// <summary>
/// Polls the control plane for work and executes it. PROTOCOL sections 4 and 6.
/// </summary>
public sealed class CommandWorker(
    AgentConfig config,
    DeviceClient client,
    EnvelopeVerifier verifier,
    PowerController power,
    LoginBroker login,
    SessionLocker locker,
    CommandWakeup wakeup,
    ILogger<CommandWorker> log) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        if (!config.IsProvisioned)
        {
            log.LogWarning("agent is not provisioned; run 'WolfAgent.exe configure' as administrator");
            return;
        }

        log.LogInformation("command worker polling every {Interval}ms", config.PollIntervalMs);

        var failures = 0;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var handled = await PollOnceAsync(ct);
                failures = handled ? 0 : failures + 1;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                failures++;
                log.LogError(ex, "poll loop error");
            }

            // Back off when the cloud is unreachable. The LAN path is still
            // live the whole time, so an offline agent is degraded, not dead.
            var delay = failures == 0
                ? config.PollIntervalMs
                : Math.Min(config.PollIntervalMs * (1 << Math.Min(failures, 4)), 60_000);

            await wakeup.WaitAsync(TimeSpan.FromMilliseconds(delay), ct);
        }
    }

    private async Task<bool> PollOnceAsync(CancellationToken ct)
    {
        using var response = await client.PostSignedAsync("device-poll", new
        {
            firmware_version = AgentInfo.Version,
            uptime_s = (int)(Environment.TickCount64 / 1000),
        }, ct);

        if (response is null) return false;

        var root = response.RootElement;

        if (root.TryGetProperty("trusted_keys", out var keys) &&
            keys.ValueKind == JsonValueKind.Array)
        {
            verifier.SetTrustedKeys(keys.EnumerateArray()
                                        .Select(k => k.GetString() ?? "")
                                        .Where(k => k.Length > 0));
        }

        if (!root.TryGetProperty("commands", out var commands) ||
            commands.ValueKind != JsonValueKind.Array)
            return true;

        foreach (var item in commands.EnumerateArray())
        {
            var envelope = item.Deserialize<Envelope>();
            if (envelope is null) continue;
            await ExecuteAsync(envelope, ct);
        }

        return true;
    }

    private async Task ExecuteAsync(Envelope envelope, CancellationToken ct)
    {
        var check = verifier.Verify(envelope, config.PcId);
        if (check != EnvelopeCheck.Ok)
        {
            log.LogWarning("rejecting {Command}: {Reason}", envelope.Command, check);
            verifier.Remember(envelope.CommandId);
            await AckAsync(envelope.CommandId, "FAILED", check.ToString(), ct);
            return;
        }

        verifier.Remember(envelope.CommandId);
        // Do not put a cloud HTTP acknowledgement ahead of the local action.
        // Its network timeout used to add up to 15 seconds before shutdown began.

        // Act first, then report what actually happened.
        //
        // The obvious-looking alternative — ack EXECUTED first, in case the
        // machine dies before a later ack gets out — is wrong, and quietly so:
        // command status is forward-only server-side, so EXECUTED is terminal
        // and a FAILED sent afterwards is absorbed and ignored. A shutdown that
        // the OS refused would report as success and the user would be told
        // "powering off" by a PC that is doing nothing of the sort.
        //
        // There is no race to lose anyway. InitiateShutdown returns as soon as
        // the shutdown is *scheduled*, so the whole grace period is available
        // to get the ack out.
        switch (envelope.Command)
        {
            case "SHUTDOWN":
            {
                var ok = power.Shutdown("remote shutdown from WolfPC", out var error);
                await AckAsync(envelope.CommandId, ok ? "EXECUTED" : "FAILED", error, ct);
                break;
            }

            case "RESTART":
            {
                var ok = power.Restart("remote restart from WolfPC", out var error);
                await AckAsync(envelope.CommandId, ok ? "EXECUTED" : "FAILED", error, ct);
                break;
            }

            case "LOCK":
            {
                var ok = locker.Lock(out var error);
                await AckAsync(envelope.CommandId, ok ? "EXECUTED" : "FAILED", error, ct);
                break;
            }

            case "LOGIN":
            {
                var (ok, error) = login.RequestUnlock(envelope.CommandId);
                await AckAsync(envelope.CommandId, ok ? "EXECUTED" : "FAILED", error, ct);
                break;
            }

            default:
                await AckAsync(envelope.CommandId, "FAILED", "unsupported command", ct);
                break;
        }
    }

    private async Task AckAsync(string commandId, string status, string? error, CancellationToken ct)
    {
        await client.PostSignedAsync("device-ack", new
        {
            command_id = commandId,
            status,
            error,
        }, ct);
    }
}

public static class AgentInfo
{
    public const string Version = "1.1.0";
}
