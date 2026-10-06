using System.Text.Json;
using Wolf.Agent.Cloud;
using Wolf.Agent.Config;
using System.Text;
using Wolf.Agent.LocalApi;
using Wolf.Agent.Pairing;
using Wolf.Agent.Security;

namespace Wolf.Agent.Cli;

/// <summary>
/// Console commands, for setup and troubleshooting. Run from an elevated
/// prompt; everything here touches %ProgramData%\WolfPC or the service.
/// </summary>
public static class CliRunner
{
    public static bool IsCliCommand(string[] args) =>
        args.Length > 0 && Verbs.Contains(args[0]);

    private static readonly HashSet<string> Verbs =
        new(StringComparer.OrdinalIgnoreCase)
        { "configure", "pair", "status", "enroll-login", "forget-login", "login-test", "help" };

    public static async Task<int> RunAsync(string[] args)
    {
        using var loggerFactory = LoggerFactory.Create(b =>
            b.AddSimpleConsole(o => o.SingleLine = true).SetMinimumLevel(LogLevel.Information));
        var log = loggerFactory.CreateLogger("wolfctl");

        return args[0].ToLowerInvariant() switch
        {
            "configure" => Configure(args),
            "pair" => await PairAsync(loggerFactory),
            "status" => Status(),
            "enroll-login" => EnrollLogin(args),
            "forget-login" => ForgetLogin(),
            "login-test" => await LoginTest.RunAsync(),
            _ => Help(),
        };
    }

    private static int Help()
    {
        Console.WriteLine("""

            WolfAgent

              WolfAgent.exe configure --pc PC-001 --device PC-001 \
                                      --url https://xxx.supabase.co --anon <key> --secret <64 hex>
              WolfAgent.exe pair        show a pairing code for the Android app
              WolfAgent.exe status      show what the agent thinks is going on

              WolfAgent.exe enroll-login [--user NAME] [--domain NAME]
                                        store the account the phone unlocks (Phase 8)
              WolfAgent.exe forget-login remove it again
              WolfAgent.exe login-test  drive the login pipe as a fake credential provider

            The service itself is installed by agent/install/install.ps1.

            """);
        return 0;
    }

    /// <summary>
    /// Store the Windows account the phone is allowed to unlock.
    ///
    /// Read <c>docs/PHASE8-LOGIN.md</c> before using this. It seals a password
    /// on this machine, which is a deliberate trade and not one to make by
    /// accident, so the command says so before it asks for anything.
    /// </summary>
    private static int EnrollLogin(string[] args)
    {
        // Before the banner and before the prompt. %ProgramData%\WolfPC is
        // writable by Users, so the first enrolment on a fresh machine
        // succeeds unelevated and then locks itself to SYSTEM and
        // administrators - and every later attempt fails on the write, after
        // the password has already been typed and validated. Asking someone
        // for their password and then discarding it is exactly the failure
        // this command exists to avoid elsewhere.
        if (!IsElevated())
        {
            Console.Error.WriteLine();
            Console.Error.WriteLine("  Run this from an elevated prompt: the credential is stored where");
            Console.Error.WriteLine("  only SYSTEM and administrators can reach it.");
            Console.Error.WriteLine();
            return 1;
        }

        string? Arg(string name)
        {
            var i = Array.FindIndex(args, a => a.Equals("--" + name, StringComparison.OrdinalIgnoreCase));
            return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
        }

        var domain = Arg("domain") ?? Environment.MachineName;
        var user = Arg("user") ?? Environment.UserName;

        Console.WriteLine();
        Console.WriteLine($"  Enrolling {domain}\\{user} for phone unlock.");
        Console.WriteLine();
        Console.WriteLine("  This seals that account's Windows password on this PC, seals it to");
        Console.WriteLine("  this machine, and releases it only when your phone has approved a");
        Console.WriteLine("  signed challenge. An administrator here could unseal it - but an");
        Console.WriteLine("  administrator here can already do anything. See docs/PHASE8-LOGIN.md.");
        Console.WriteLine();

        var password = ReadPasswordMasked("  Windows password: ");
        Console.WriteLine();

        if (password.Length == 0)
        {
            Console.WriteLine("  Nothing entered; nothing stored.");
            return 1;
        }

        try
        {
            CredentialVault.Store(domain, user, password);
            Console.WriteLine("  Stored, and checked against Windows.");
            Console.WriteLine();
            Console.WriteLine("  Re-run this after any Windows password change: the sealed copy");
            Console.WriteLine("  does not follow it, and the tile will simply stop working.");
            return 0;
        }
        catch (UnauthorizedAccessException ex)
        {
            Console.WriteLine($"  {ex.Message}");
            return 1;
        }
        finally
        {
            Array.Clear(password);
        }
    }

    private static int ForgetLogin()
    {
        if (!IsElevated())
        {
            Console.Error.WriteLine();
            Console.Error.WriteLine("  Run this from an elevated prompt.");
            Console.Error.WriteLine();
            return 1;
        }

        if (!CredentialVault.Exists)
        {
            Console.WriteLine("  Nothing was enrolled.");
            return 0;
        }
        CredentialVault.Forget();
        Console.WriteLine("  Removed. Phone unlock will answer \"not enrolled\" until you run");
        Console.WriteLine("  enroll-login again. The password tile is unaffected.");
        return 0;
    }

    /// <summary>
    /// Read a password without echoing it, into a buffer the caller can wipe.
    /// <c>Console.ReadLine</c> would both echo it and leave it in a string that
    /// cannot be cleared.
    /// </summary>
    private static char[] ReadPasswordMasked(string prompt)
    {
        Console.Write(prompt);

        var buffer = new char[256];
        var length = 0;

        while (true)
        {
            var key = Console.ReadKey(intercept: true);

            if (key.Key == ConsoleKey.Enter) break;

            if (key.Key == ConsoleKey.Backspace)
            {
                if (length > 0) { buffer[--length] = '\0'; Console.Write("\b \b"); }
                continue;
            }

            if (key.KeyChar == '\0' || char.IsControl(key.KeyChar)) continue;
            if (length == buffer.Length) continue;

            buffer[length++] = key.KeyChar;
            Console.Write('*');
        }

        var result = buffer[..length];
        Array.Clear(buffer);
        return result;
    }

    private static bool IsElevated() =>
        new System.Security.Principal.WindowsPrincipal(
            System.Security.Principal.WindowsIdentity.GetCurrent())
            .IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);

    private static int Configure(string[] args)
    {
        var config = AgentConfig.Load();

        string? Arg(string name)
        {
            var i = Array.FindIndex(args, a => a.Equals("--" + name, StringComparison.OrdinalIgnoreCase));
            return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
        }

        config.PcId = Arg("pc") ?? config.PcId;
        config.DeviceId = Arg("device") ?? config.DeviceId;
        config.SupabaseUrl = Arg("url") ?? config.SupabaseUrl;
        config.SupabaseAnonKey = Arg("anon") ?? config.SupabaseAnonKey;
        if (int.TryParse(Arg("port"), out var port)) config.LocalApiPort = port;

        var secret = Arg("secret");
        if (secret is not null)
        {
            try
            {
                config.SetSecretFromHex(secret);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"secret rejected: {ex.Message}");
                return 1;
            }
        }

        config.Save();
        Console.WriteLine($"""

            saved to {AgentConfig.ConfigPath}

              pc id       {config.PcId}
              device id   {config.DeviceId}
              supabase    {config.SupabaseUrl}
              lan port    {config.LocalApiPort}
              secret      {(config.IsProvisioned ? "sealed with DPAPI" : "MISSING")}

            """);
        return config.IsProvisioned ? 0 : 1;
    }

    private static int Status()
    {
        var config = AgentConfig.Load();
        Console.WriteLine($"""

            pc id        {config.PcId}
            device id    {config.DeviceId}
            supabase     {config.SupabaseUrl}
            provisioned  {config.IsProvisioned}
            config file  {AgentConfig.ConfigPath}

            """);

        if (File.Exists(AgentConfig.CertPath))
        {
            var certificate = System.Security.Cryptography.X509Certificates
                .X509CertificateLoader.LoadPkcs12FromFile(AgentConfig.CertPath, null);
            Console.WriteLine($"LAN certificate fingerprint (pin this in the app):");
            Console.WriteLine($"  {LanCertificate.Fingerprint(certificate)}\n");
        }

        // Non-zero when there is nothing to run on, so install.ps1 can ask
        // whether this machine is already configured rather than inferring it
        // from which arguments it happened to be given.
        return config.IsProvisioned ? 0 : 1;
    }

    private static async Task<int> PairAsync(ILoggerFactory loggerFactory)
    {
        var config = AgentConfig.Load();
        if (!config.IsProvisioned)
        {
            Console.Error.WriteLine("agent is not configured yet; run 'WolfAgent.exe configure' first");
            return 1;
        }

        var client = new DeviceClient(config, loggerFactory.CreateLogger<DeviceClient>());
        using var response = await client.PostSignedAsync(
            "create-pairing-code", new { }, CancellationToken.None);

        if (response is null)
        {
            Console.Error.WriteLine("could not reach the control plane; check the network and the clock");
            return 1;
        }

        var code = response.RootElement.GetProperty("code").GetString() ?? "";
        var expires = response.RootElement.GetProperty("expires_at").GetString();

        var fingerprint = PairingQr.Fingerprint();
        var payload = PairingQr.BuildPayload(config, code, fingerprint);

        Console.OutputEncoding = Encoding.UTF8;
        Console.WriteLine();
        Console.WriteLine("  Scan this with the WolfPC app:");
        Console.WriteLine();
        Console.Write(PairingQr.RenderAnsi(payload));

        var png = PairingQr.WritePng(payload);

        Console.WriteLine($"""

              PAIRING CODE   {code}

            Scanning also carries over the project settings and this PC's local
            address and certificate fingerprint, so the offline path works without
            typing any of it. Typing the code by hand pairs just as well; you would
            then fill in Settings, Home network yourself.

            Valid until {expires} (10 minutes). Single use.

            If the block drawing above is mangled, open: {png}

            """);

        if (fingerprint is null)
        {
            Console.WriteLine(
                "  Note: no LAN certificate yet, so the QR carries no local address.\n" +
                "  Start the service once, then run pair again.\n");
        }

        PairingQr.TryOpen(png);
        return 0;
    }
}
