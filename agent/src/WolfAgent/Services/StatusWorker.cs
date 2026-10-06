using Wolf.Agent.Cloud;
using Wolf.Agent.Config;
using Wolf.Agent.Pairing;

namespace Wolf.Agent.Services;

/// <summary>
/// Heartbeat and Windows state. This is the only source of windows_state in
/// the whole system (PRD §28) — if this worker stops, the app shows UNKNOWN
/// rather than guessing.
///
/// The loop ticks once a second and decides whether to report, rather than
/// sleeping for the whole heartbeat interval. That matters for two reasons:
///
///  - a state change is news, and news should not wait for a timer. The login
///    screen appearing is the single moment the user is watching for, and
///    posting it the moment it is observed is the difference between the app
///    feeling live and feeling broken.
///  - the service starts at boot, before the network stack is ready, so the
///    first few attempts always fail. Backing off from one second rather than
///    the full heartbeat means the first successful report lands seconds after
///    the network appears instead of up to a minute later.
/// </summary>
public sealed class StatusWorker(
    AgentConfig config,
    DeviceClient client,
    SessionMonitor sessions,
    LoginBroker login,
    ILogger<StatusWorker> log) : BackgroundService
{
    private WindowsState _lastWindows = WindowsState.UNKNOWN;
    private SessionState _lastSession = SessionState.NONE;
    private bool? _lastLoginAvailable;

    private static readonly TimeSpan Tick = TimeSpan.FromSeconds(1);

    // Re-read rather than cache forever: DHCP moves this, and a stale address
    // reported confidently is worse than none at all. Enumerating interfaces is
    // a few milliseconds, so once a minute costs nothing and keeps the app's
    // cached address correct within a minute of any change.
    private static readonly TimeSpan AddressRefresh = TimeSpan.FromMinutes(1);
    private string? _lanAddress;
    private bool _networkPrivate = true;
    private DateTimeOffset _addressCheckedAt = DateTimeOffset.MinValue;

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        if (!config.IsProvisioned) return;

        var heartbeat = TimeSpan.FromMilliseconds(config.HeartbeatIntervalMs);
        var lastReport = DateTimeOffset.MinValue;
        var everSucceeded = false;
        var failures = 0;
        var nextRetry = DateTimeOffset.MinValue;

        while (!ct.IsCancellationRequested)
        {
            try
            {
                RefreshLanFacts();

                var (windows, session, user) = sessions.Read();
                var loginAvailable = login.ProviderConnected;
                var changed = windows != _lastWindows || session != _lastSession ||
                              loginAvailable != _lastLoginAvailable;
                var now = DateTimeOffset.UtcNow;

                var due = changed
                    || now - lastReport >= heartbeat
                    || (!everSucceeded && now >= nextRetry);

                if (due)
                {
                    if (changed)
                    {
                        log.LogInformation("state {Windows}/{Session}", windows, session);
                        _lastWindows = windows;
                        _lastSession = session;
                        _lastLoginAvailable = loginAvailable;
                    }

                    var response = await client.PostSignedAsync("agent-status", new
                    {
                        windows_state = windows.ToString(),
                        session_state = session.ToString(),
                        agent_version = AgentInfo.Version,
                        username = user,
                        phone_login_available = loginAvailable,
                        // Where to find this PC on the LAN, so the app stops
                        // relying on whatever address pairing happened to
                        // capture. DHCP moves it; this is how the app finds out.
                        lan_address = _lanAddress,
                        network_private = _networkPrivate,
                    }, ct);

                    if (response is not null)
                    {
                        response.Dispose();
                        lastReport = DateTimeOffset.UtcNow;
                        failures = 0;
                        if (!everSucceeded)
                        {
                            everSucceeded = true;
                            log.LogInformation("first status report delivered");
                        }
                    }
                    else
                    {
                        // Back off from one second, doubling to the heartbeat
                        // interval. At boot this is almost always "the network
                        // is not up yet", which resolves on its own in a few
                        // seconds and should not cost a minute of silence.
                        failures++;
                        var backoff = TimeSpan.FromSeconds(Math.Min(1 << Math.Min(failures, 4),
                                                                    heartbeat.TotalSeconds));
                        nextRetry = DateTimeOffset.UtcNow + backoff;

                        // A change we failed to deliver has to be re-detected,
                        // or the retry would report the state as unchanged and
                        // the transition would be lost for good.
                        if (changed)
                        {
                            _lastWindows = WindowsState.UNKNOWN;
                            _lastSession = SessionState.NONE;
                            _lastLoginAvailable = null;
                        }
                    }
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                log.LogWarning("status report failed: {Message}", ex.Message);
            }

            await Task.Delay(Tick, ct).ConfigureAwait(false);
        }
    }

    private void RefreshLanFacts()
    {
        var now = DateTimeOffset.UtcNow;
        if (now - _addressCheckedAt < AddressRefresh) return;
        _addressCheckedAt = now;

        var ip = PairingQr.LocalIPv4();
        var address = ip is null ? null : $"{ip}:{config.LocalApiPort}";
        if (address != _lanAddress && address is not null)
        {
            log.LogInformation("lan address is now {Address}", address);
        }
        _lanAddress = address;

        var isPrivate = NetworkProfile.IsPrivate(log);
        if (isPrivate != _networkPrivate)
        {
            if (isPrivate) log.LogInformation("network is Private; the LAN firewall rule applies");
            else log.LogWarning(
                "network is classified Public, so the LAN firewall rule is inert and " +
                "the phone cannot reach this PC directly. Set the network profile to Private.");
        }
        _networkPrivate = isPrivate;
    }

    /// <summary>Current state, for the LAN status endpoint.</summary>
    public (WindowsState Windows, SessionState Session) Snapshot() => (_lastWindows, _lastSession);
}
