using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using Wolf.Agent.Security;

namespace Wolf.Agent.Services;

/// <summary>
/// The agent's side of the Phase 8 phone-unlock feature (PRD section 15).
///
/// The credential provider runs inside LogonUI, a heavily restricted process
/// that cannot reach the network. So the split is: the provider owns the UI and
/// the credential submission, this service owns the network and the crypto, and
/// they talk over a named pipe ACL'd to SYSTEM and administrators.
///
/// Until the credential provider is installed nothing connects to the pipe, and
/// a LOGIN command is answered with a clear "not installed" rather than left to
/// time out. That is deliberate: the app should say plainly that the feature is
/// absent instead of showing a button that silently fails.
///
/// ## Wire protocol
///
/// One line per message, UTF-8, newline terminated. The provider asks, this
/// service answers. Every value that could contain a space is base64.
///
/// <code>
/// HELLO           -> WOLF 1
/// PENDING         -> NONE | UNLOCK &lt;token&gt;
/// CHALLENGE       -> CHALLENGE &lt;challenge_id&gt; | ERR &lt;reason&gt;
/// AWAIT &lt;cid&gt;     -> WAITING | APPROVED &lt;token&gt; | DENIED | EXPIRED | ERR &lt;reason&gt;
/// CLAIM &lt;token&gt;   -> CRED &lt;b64 domain&gt; &lt;b64 user&gt; &lt;b64 utf16 password&gt; | ERR &lt;reason&gt;
/// CANCEL &lt;cid&gt;    -> OK
/// WHO             -> WHO &lt;b64 domain&gt; &lt;b64 user&gt; | ERR &lt;reason&gt;
/// LOG &lt;text&gt;      -> OK
/// </code>
///
/// There are two ways in and they converge. <c>PENDING</c> serves the
/// phone-initiated path, where a signed LOGIN command has already been verified
/// and the approval is proven before the tile is ever touched. <c>CHALLENGE</c>
/// and <c>AWAIT</c> serve the provider-initiated path, which is what phone
/// unlock means to a person: click the tile, the phone asks, approve it. Both
/// end holding a one-shot token, and <c>CLAIM</c> is the only way a credential
/// leaves this process.
///
/// The password does cross the pipe. That is worth being explicit about: the
/// pipe is ACL'd to SYSTEM and administrators on the local machine, and the
/// provider must hold the plaintext regardless because it has to hand it to
/// Windows. What the pipe buys is that the *release* is gated on a verified
/// phone approval rather than on merely being SYSTEM.
/// </summary>
public sealed class LoginBroker(LoginFlow flow, ILogger<LoginBroker> log) : BackgroundService
{
    public const string PipeName = "WolfPC.Login";

    /// <summary>
    /// UTF-8 with no byte order mark.
    ///
    /// Encoding.UTF8 emits one, and StreamWriter writes it at the head of the
    /// stream - so the very first reply on this pipe went out as EF BB BF
    /// followed by "WOLF 1". .NET clients never noticed, because StreamReader
    /// strips a BOM by default; that is precisely why login-test passed while
    /// the real credential provider failed its handshake and hung up, three
    /// seconds at a time, forever. A line protocol has no business carrying a
    /// BOM at all.
    /// </summary>
    private static readonly Encoding PipeEncoding = new UTF8Encoding(false);

    private volatile bool _providerConnected;
    private volatile string? _pendingToken;

    public bool ProviderConnected => _providerConnected;

    /// <summary>
    /// Called when a LOGIN command arrives from the phone. The envelope has
    /// already been verified, so this mints a grant directly rather than asking
    /// the phone to prove the same thing a second time.
    /// </summary>
    public (bool Ok, string? Error) RequestUnlock(string commandId)
    {
        if (!_providerConnected)
            return (false, "phone login is not installed on this PC; use the password tile");

        if (!CredentialVault.Exists)
            return (false, "no credential is enrolled on this PC; run WolfAgent.exe enroll-login");

        _pendingToken = flow.MintGrant();
        log.LogInformation("login requested by command {CommandId}", commandId);
        return (true, null);
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await ServeOneAsync(ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                log.LogWarning("login pipe error: {Message}", ex.Message);
                await Task.Delay(2000, ct);
            }
        }
    }

    private async Task ServeOneAsync(CancellationToken ct)
    {
        // LogonUI runs as SYSTEM, so the ACL grants SYSTEM and administrators
        // and nobody else. A pipe any logged-in user could open would be a way
        // to drive the login flow from a standard account.
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            PipeAccessRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            PipeAccessRights.FullControl, AccessControlType.Allow));

        using var server = NamedPipeServerStreamAcl.Create(
            PipeName, PipeDirection.InOut, 1,
            PipeTransmissionMode.Byte, PipeOptions.Asynchronous,
            inBufferSize: 4096, outBufferSize: 4096, pipeSecurity: security);

        await server.WaitForConnectionAsync(ct);
        _providerConnected = true;
        log.LogInformation("credential provider connected");

        try
        {
            using var reader = new StreamReader(server, Encoding.UTF8, leaveOpen: true);
            await using var writer = new StreamWriter(server, PipeEncoding, leaveOpen: true)
                { AutoFlush = true };

            while (!ct.IsCancellationRequested && server.IsConnected)
            {
                var line = await reader.ReadLineAsync(ct);
                if (line is null) break;
                await HandleAsync(line, writer, ct);
            }
        }
        finally
        {
            _providerConnected = false;
            log.LogInformation("credential provider disconnected");
        }
    }

    private async Task HandleAsync(string line, StreamWriter writer, CancellationToken ct)
    {
        var space = line.IndexOf(' ');
        var verb = space < 0 ? line : line[..space];
        var arg = space < 0 ? "" : line[(space + 1)..].Trim();

        switch (verb)
        {
            case "HELLO":
                await writer.WriteLineAsync("WOLF 1");
                break;

            case "PENDING":
            {
                var token = _pendingToken;
                _pendingToken = null;
                await writer.WriteLineAsync(token is null ? "NONE" : $"UNLOCK {token}");
                break;
            }

            case "CHALLENGE":
            {
                var (id, error) = await flow.CreateChallengeAsync(ct);
                await writer.WriteLineAsync(id is null ? $"ERR {error}" : $"CHALLENGE {id}");
                break;
            }

            case "AWAIT":
            {
                if (arg.Length == 0) { await writer.WriteLineAsync("ERR missing challenge id"); break; }

                var result = await flow.AwaitAsync(arg, ct);
                await writer.WriteLineAsync(result.State switch
                {
                    LoginWait.Waiting  => "WAITING",
                    LoginWait.Approved => $"APPROVED {result.Token}",
                    LoginWait.Denied   => "DENIED",
                    LoginWait.Expired  => "EXPIRED",
                    _                  => $"ERR {result.Error ?? "login failed"}",
                });
                break;
            }

            case "CLAIM":
                await ClaimAsync(arg, writer);
                break;

            // The identity only, never the secret. The provider needs it before
            // any approval exists: at the lock screen Windows expects an unlock
            // tile to be bound to the account being unlocked, and resolving a
            // SID needs a name. Handing over "which account" is not a
            // disclosure - anyone at that screen can read it off the tile
            // Windows already drew.
            case "WHO":
            {
                using var credential = CredentialVault.Unseal();
                await writer.WriteLineAsync(credential is null
                    ? "ERR nothing enrolled"
                    : $"WHO {Convert.ToBase64String(Encoding.UTF8.GetBytes(credential.Domain))} " +
                      $"{Convert.ToBase64String(Encoding.UTF8.GetBytes(credential.Username))}");
                break;
            }

            // The provider runs inside LogonUI, where there is no console, no
            // debugger worth attaching and no log of its own. Without this the
            // only way to find out what it did is to guess - and this project
            // has already paid for that lesson once. It borrows the agent's
            // event log, which is readable from a normal desktop session after
            // the fact.
            case "LOG":
                if (arg.Length > 0) log.LogInformation("provider: {Message}", Trim(arg));
                await writer.WriteLineAsync("OK");
                break;

            case "CANCEL":
                if (arg.Length > 0) await flow.CancelAsync(arg, ct);
                await writer.WriteLineAsync("OK");
                break;

            default:
                await writer.WriteLineAsync("ERR unsupported");
                break;
        }
    }

    /// <summary>Bounded, single-line: this writes to an event log.</summary>
    private static string Trim(string text)
    {
        var flat = text.Replace((char)10, ' ').Replace((char)13, ' ');
        return flat.Length <= 300 ? flat : flat[..300];
    }

    private async Task ClaimAsync(string token, StreamWriter writer)
    {
        if (token.Length == 0)
        {
            await writer.WriteLineAsync("ERR missing token");
            return;
        }

        using var credential = flow.Redeem(token);
        if (credential is null)
        {
            // Covers an unknown token, an expired one, a second attempt at a
            // token already spent, and a vault that will not open. The provider
            // gets one message for all of them on purpose: at a login screen
            // the difference is not actionable, and spelling it out would tell
            // anyone watching which of those states they had reached.
            await writer.WriteLineAsync("ERR no credential available");
            log.LogWarning("credential claim refused");
            return;
        }

        var domain = Convert.ToBase64String(Encoding.UTF8.GetBytes(credential.Domain));
        var user = Convert.ToBase64String(Encoding.UTF8.GetBytes(credential.Username));

        // Base64 straight into a buffer we own. Convert.ToBase64String would
        // put the password on the managed heap as a string that cannot be
        // cleared and would outlive this method in any crash dump.
        var encoded = new char[((credential.PasswordUtf16.Length + 2) / 3) * 4];
        try
        {
            Convert.TryToBase64Chars(credential.PasswordUtf16, encoded, out var written);
            await writer.WriteAsync($"CRED {domain} {user} ");
            await writer.WriteAsync(encoded.AsMemory(0, written));
            await writer.WriteAsync('\n');
        }
        finally
        {
            Array.Clear(encoded);
        }

        log.LogInformation("credential released to the provider");
    }
}
