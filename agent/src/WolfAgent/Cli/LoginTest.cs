using System.IO.Pipes;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using Wolf.Agent.Services;

namespace Wolf.Agent.Cli;

/// <summary>
/// Stand in for the credential provider, so the rest of Phase 8 can be proven
/// before a line of C++ exists.
///
/// This speaks the same pipe protocol <see cref="LoginBroker"/> serves, from a
/// console where every step is visible. Running it exercises the whole chain
/// with the real phone and the real Supabase project: challenge created,
/// prompt delivered, fingerprint presented, signature verified locally against
/// a pinned key, credential released. What the eventual provider adds on top of
/// that is a tile and a call into Windows - which is worth knowing, because a
/// bug in a credential provider costs a virtual machine and a snapshot to find,
/// and a bug in any of the above costs a console window.
///
/// It never prints the password. It reports the length and nothing else: the
/// point of the exercise is to prove the credential arrived, and a plaintext
/// password in a scrollback buffer would undo most of what the vault is for.
/// </summary>
public static class LoginTest
{
    /// <summary>UTF-8 with no BOM, matching LoginBroker. See the note there.</summary>
    private static readonly Encoding PipeEncoding = new UTF8Encoding(false);

    public static async Task<int> RunAsync(CancellationToken ct = default)
    {
        if (!IsElevated())
        {
            Console.Error.WriteLine(
                "  Run this from an elevated prompt. The login pipe is ACL'd to SYSTEM\n" +
                "  and administrators, which is what stops a standard account driving\n" +
                "  the login flow.");
            return 1;
        }

        Console.WriteLine("""

              Phase 8 test harness - pretending to be the credential provider.

              The agent cannot tell this apart from the real thing, so while it is
              connected the app will report phone login as available.

            """);

        await using var pipe = new NamedPipeClientStream(
            ".", LoginBroker.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);

        try
        {
            await pipe.ConnectAsync(5000, ct);
        }
        catch (TimeoutException)
        {
            Console.Error.WriteLine(
                "  Nothing is serving the login pipe. Is the WolfPCAgent service running?");
            return 1;
        }

        using var reader = new StreamReader(pipe, Encoding.UTF8, leaveOpen: true);
        await using var writer = new StreamWriter(pipe, PipeEncoding, leaveOpen: true)
            { AutoFlush = true };

        async Task<string> AskAsync(string line, bool echo = true)
        {
            if (echo) Console.WriteLine($"  -> {line}");
            await writer.WriteLineAsync(line.AsMemory(), ct);
            var reply = await reader.ReadLineAsync(ct) ?? "";
            return reply;
        }

        var hello = await AskAsync("HELLO");
        Console.WriteLine($"  <- {hello}");
        if (!hello.StartsWith("WOLF ", StringComparison.Ordinal))
        {
            Console.Error.WriteLine("  That is not the WolfPC login pipe.");
            return 1;
        }

        var opened = await AskAsync("CHALLENGE");
        Console.WriteLine($"  <- {opened}");

        if (!opened.StartsWith("CHALLENGE ", StringComparison.Ordinal))
        {
            // Much the most likely answer the first time: nothing is enrolled.
            // The broker refuses early on purpose, rather than letting someone
            // pick up a phone and touch a sensor for a login that cannot work.
            Console.Error.WriteLine("""

                  The agent would not open a challenge.

                  If it says nothing is enrolled, that is expected until you run:

                      WolfAgent.exe enroll-login

                  which is reversible with forget-login.

                """);
            return 1;
        }

        var challengeId = opened["CHALLENGE ".Length..].Trim();
        Console.WriteLine("""

              Check your phone. It has about 60 seconds.

            """);

        string? token = null;
        var deadline = DateTimeOffset.UtcNow.AddSeconds(75);
        var lastPrinted = "";

        while (DateTimeOffset.UtcNow < deadline)
        {
            var state = await AskAsync($"AWAIT {challengeId}", echo: false);

            if (state != lastPrinted)
            {
                Console.WriteLine($"  <- {state}");
                lastPrinted = state;
            }

            if (state.StartsWith("APPROVED ", StringComparison.Ordinal))
            {
                token = state["APPROVED ".Length..].Trim();
                break;
            }
            if (state is "DENIED" or "EXPIRED" || state.StartsWith("ERR", StringComparison.Ordinal))
                return 1;

            await Task.Delay(1000, ct);
        }

        if (token is null)
        {
            Console.Error.WriteLine("  Nothing came back in time.");
            await AskAsync($"CANCEL {challengeId}", echo: false);
            return 1;
        }

        Console.WriteLine();
        Console.WriteLine("  Approved, and the signature verified against a key this PC pinned.");
        Console.WriteLine();

        await writer.WriteLineAsync($"CLAIM {token}".AsMemory(), ct);
        var released = await reader.ReadLineAsync(ct) ?? "";

        if (!released.StartsWith("CRED ", StringComparison.Ordinal))
        {
            Console.Error.WriteLine($"  <- {released}");
            return 1;
        }

        var fields = released.Split(' ');
        if (fields.Length != 4)
        {
            Console.Error.WriteLine("  Malformed credential line.");
            return 1;
        }

        var domain = Encoding.UTF8.GetString(Convert.FromBase64String(fields[1]));
        var user = Encoding.UTF8.GetString(Convert.FromBase64String(fields[2]));

        var password = Convert.FromBase64String(fields[3]);
        var characters = password.Length / sizeof(char);
        CryptographicOperations.ZeroMemory(password);

        Console.WriteLine($"""
              Credential released for {domain}\{user} - {characters} characters.

              Not printed, and wiped from this process. That is the whole chain:
              the phone approved, this PC verified it, and the vault opened once.

              Claiming the same token again should fail. Checking:
            """);

        await writer.WriteLineAsync($"CLAIM {token}".AsMemory(), ct);
        var replay = await reader.ReadLineAsync(ct) ?? "";
        Console.WriteLine($"  <- {replay}");
        Console.WriteLine(
            replay.StartsWith("ERR", StringComparison.Ordinal)
                ? "\n  Good - one shot only.\n"
                : "\n  PROBLEM: the token was accepted twice.\n");

        return replay.StartsWith("ERR", StringComparison.Ordinal) ? 0 : 1;
    }

    private static bool IsElevated() =>
        new WindowsPrincipal(WindowsIdentity.GetCurrent())
            .IsInRole(WindowsBuiltInRole.Administrator);
}
