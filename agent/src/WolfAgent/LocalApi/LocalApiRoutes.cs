using System.Text.Json;
using Wolf.Agent.Config;
using Wolf.Agent.Security;
using Wolf.Agent.Services;

namespace Wolf.Agent.LocalApi;

/// <summary>
/// The LAN fallback (PRD §29): graceful shutdown and restart keep working at
/// home when the internet is down.
///
/// Authentication is the same signed envelope the cloud path carries, verified
/// against the phone keys the agent learned from its last successful poll. No
/// "the LAN is trusted" shortcut — PRD §18 rules that out explicitly.
/// </summary>
public static class LocalApiRoutes
{
    public static void Map(WebApplication app)
    {
        var config = app.Services.GetRequiredService<AgentConfig>();
        var verifier = app.Services.GetRequiredService<EnvelopeVerifier>();
        var power = app.Services.GetRequiredService<PowerController>();
        var locker = app.Services.GetRequiredService<SessionLocker>();
        var login = app.Services.GetRequiredService<LoginBroker>();
        var sessions = app.Services.GetRequiredService<SessionMonitor>();
        var log = app.Services.GetRequiredService<ILoggerFactory>().CreateLogger("LocalApi");

        app.MapGet("/api/status", () =>
        {
            var (windows, session, user) = sessions.Read();
            return Results.Json(new
            {
                pc_id = config.PcId,
                agent_version = AgentInfo.Version,
                windows_state = windows.ToString(),
                session_state = session.ToString(),
                username = user,
                uptime_s = Environment.TickCount64 / 1000,
                trusted_keys = verifier.TrustedKeyCount,
                phone_login_available = login.ProviderConnected,
            });
        });

        app.MapPost("/api/shutdown", async (HttpRequest request) =>
            await Act(request, "SHUTDOWN", verifier, config, log,
                      () => power.Shutdown("remote shutdown over LAN", out var e) ? null : e));

        app.MapPost("/api/restart", async (HttpRequest request) =>
            await Act(request, "RESTART", verifier, config, log,
                      () => power.Restart("remote restart over LAN", out var e) ? null : e));

        app.MapPost("/api/lock", async (HttpRequest request) =>
            await Act(request, "LOCK", verifier, config, log,
                      () => locker.Lock(out var e) ? null : e));

        app.MapFallback(() => Results.Json(
            new { error = new { code = "not_found", message = "no" } }, statusCode: 404));
    }

    private static async Task<IResult> Act(
        HttpRequest request, string expectedCommand,
        EnvelopeVerifier verifier, AgentConfig config, ILogger log,
        Func<string?> action)
    {
        Envelope? envelope;
        try
        {
            // A signed envelope is a few hundred bytes. Anything larger is
            // either a bug or someone probing, and neither deserves memory.
            request.EnableBuffering();
            if (request.ContentLength > 8192)
                return Error(400, "bad_request", "body too large");

            envelope = await JsonSerializer.DeserializeAsync<Envelope>(request.Body);
        }
        catch (JsonException)
        {
            return Error(400, "bad_request", "body is not valid json");
        }

        if (envelope is null) return Error(400, "bad_request", "envelope missing");

        // The route and the signed command must agree, or a RESTART envelope
        // could be replayed against /api/shutdown.
        if (!string.Equals(envelope.Command, expectedCommand, StringComparison.Ordinal))
            return Error(400, "bad_request", "command does not match this endpoint");

        var check = verifier.Verify(envelope, config.PcId);
        if (check != EnvelopeCheck.Ok)
        {
            log.LogWarning("LAN {Command} rejected: {Reason}", expectedCommand, check);
            var status = check switch
            {
                EnvelopeCheck.Replay => 409,
                EnvelopeCheck.Expired => 410,
                EnvelopeCheck.BadSignature => 401,
                EnvelopeCheck.NoTrustedKey => 403,
                _ => 400,
            };
            return Error(status, check.ToString().ToLowerInvariant(), "envelope rejected");
        }

        verifier.Remember(envelope.CommandId);

        var error = action();
        if (error is not null) return Error(500, "internal", error);

        log.LogInformation("LAN {Command} executed ({Id})", expectedCommand, envelope.CommandId);
        return Results.Json(new { command_id = envelope.CommandId, status = "EXECUTED" });
    }

    private static IResult Error(int status, string code, string message) =>
        Results.Json(new { error = new { code, message } }, statusCode: status);
}
