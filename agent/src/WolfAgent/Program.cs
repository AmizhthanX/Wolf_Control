using System.Net;
using Microsoft.Extensions.Logging.EventLog;
using Wolf.Agent.Cli;
using Wolf.Agent.Cloud;
using Wolf.Agent.Config;
using Wolf.Agent.LocalApi;
using Wolf.Agent.Security;
using Wolf.Agent.Services;

// WolfPC Windows Agent (PRD §14).
//
// Runs as LocalSystem and starts before anybody logs in, which is what lets it
// report the login screen and, in Phase 8, serve the credential provider.

if (args.Length == 1 && args[0] == "session-lock")
    return SessionLocker.LockCurrentDesktop();

if (CliRunner.IsCliCommand(args))
{
    return await CliRunner.RunAsync(args);
}

var config = AgentConfig.Load();

var builder = WebApplication.CreateBuilder(args);

builder.Host.UseWindowsService(options => options.ServiceName = "WolfPCAgent");

builder.Logging.ClearProviders();
builder.Logging.AddSimpleConsole(o => o.SingleLine = true);
if (OperatingSystem.IsWindows())
{
    // The service has no console, so the event log is where its history
    // actually lives. The source is created by install.ps1, which runs
    // elevated; creating it from here would fail as a non-admin.
    builder.Logging.AddEventLog(new EventLogSettings
    {
        SourceName = "WolfPCAgent",
        LogName = "Application",
    });

    // The event log provider filters to Warning by default, which silently
    // drops every Information line this service writes — including the state
    // transitions and "first status report delivered", which are exactly what
    // you need when diagnosing a timing problem on a machine you cannot attach
    // a debugger to. The service has no console, so this log is its only
    // history; a handful of lines per minute is a fair price for being able to
    // see what it did.
    builder.Logging.AddFilter<EventLogLoggerProvider>(
        "Wolf.Agent", LogLevel.Information);
}

builder.Services.AddSingleton(config);
builder.Services.AddSingleton<EnvelopeVerifier>();
builder.Services.AddSingleton<DeviceClient>();
builder.Services.AddSingleton<SessionMonitor>();
builder.Services.AddSingleton<PowerController>();
builder.Services.AddSingleton<SessionLocker>();
builder.Services.AddSingleton<CommandWakeup>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<CommandWakeup>());
builder.Services.AddSingleton<LoginFlow>();
builder.Services.AddSingleton<LoginBroker>();
builder.Services.AddSingleton<StatusWorker>();

builder.Services.AddHostedService<CommandWorker>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<StatusWorker>());
builder.Services.AddHostedService(sp => sp.GetRequiredService<LoginBroker>());

// -------------------------------------------------------------- LAN API ----
//
// Bound to the LAN only, never to the internet: PRD §9 keeps every device's
// connection outbound, and an inbound port open to the world is exactly the
// thing that design avoids.
if (config.LocalApiEnabled)
{
    builder.WebHost.ConfigureKestrel(kestrel =>
    {
        var certificate = LanCertificate.LoadOrCreate(
            LoggerFactory.Create(b => b.AddSimpleConsole()).CreateLogger("cert"));

        kestrel.Listen(IPAddress.Any, config.LocalApiPort,
            listen => listen.UseHttps(certificate));

        // Small, quick limits. The only legitimate caller is a phone on the
        // same Wi-Fi sending a few hundred bytes.
        kestrel.Limits.MaxRequestBodySize = 16 * 1024;
        kestrel.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(10);
        kestrel.AddServerHeader = false;
    });
}
else
{
    builder.WebHost.UseUrls();
}

var app = builder.Build();

if (config.LocalApiEnabled) LocalApiRoutes.Map(app);

var startupLog = app.Services.GetRequiredService<ILoggerFactory>().CreateLogger("startup");
startupLog.LogInformation("WolfPC agent {Version} starting; pc={PcId} provisioned={Provisioned}",
    AgentInfo.Version, config.PcId, config.IsProvisioned);

if (!config.IsProvisioned)
{
    startupLog.LogWarning(
        "not provisioned - run 'WolfAgent.exe configure' from an elevated prompt. " +
        "The service will idle until then.");
}

await app.RunAsync();
return 0;
