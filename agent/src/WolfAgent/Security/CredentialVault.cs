using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;

namespace Wolf.Agent.Security;

/// <summary>The Windows account the phone unlocks. Clear it when you are done.</summary>
public sealed class LogonCredential(string domain, string username, byte[] password) : IDisposable
{
    public string Domain { get; } = domain;
    public string Username { get; } = username;

    /// <summary>UTF-16LE bytes, not a string, so it can actually be wiped.</summary>
    public byte[] PasswordUtf16 { get; private set; } = password;

    public void Dispose()
    {
        CryptographicOperations.ZeroMemory(PasswordUtf16);
        PasswordUtf16 = [];
    }
}

/// <summary>
/// The Windows password, sealed on disk, released only once a phone signature
/// has verified. This is the part of Phase 8 people are right to be uneasy
/// about, so the reasoning is worth stating plainly.
///
/// PRD section 15 wanted a custom LSA authentication package, where no password
/// exists at all. That is the stronger design, and it is also the one where a
/// bug in a DLL loaded by lsass.exe leaves a machine nobody can log into.
/// docs/PHASE8-LOGIN.md took the other trade: Windows keeps authenticating the
/// user through its own Kerberos/NTLM path, and the phone decides only whether
/// the credential is *released*. The cost is this file. The benefit is that the
/// worst failure is a tile that does not work.
///
/// Sealed with DPAPI at machine scope, exactly as AgentConfig seals the device
/// secret and for the same reason: the service runs as LocalSystem before any
/// user has logged in, so there is no user profile to key it to. The ciphertext
/// is useless on another machine; an administrator on this machine could unseal
/// it, but an administrator here can already do anything.
///
/// The credential provider never unseals this itself. It runs as SYSTEM inside
/// LogonUI and could, in principle, make its own DPAPI call - which is exactly
/// why it does not. Unsealing sits behind the signature check, so the only path
/// to the password is one a phone has already approved. If the provider could
/// open the vault alone, anything running as SYSTEM could take the password
/// with no phone involved, and the approval step would be decoration.
/// </summary>
public static class CredentialVault
{
    private static string VaultPath =>
        Path.Combine(Config.AgentConfig.Directory, "logon.cred");

    // Its own entropy, distinct from the device secret's, so neither blob can
    // ever be swapped in for the other.
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("WolfPC/logon-credential/v1");

    public static bool Exists => File.Exists(VaultPath);

    /// <summary>
    /// Validate the credential against Windows, then seal it.
    ///
    /// Validating first matters more than it looks. A mistyped password sealed
    /// here does not fail until somebody is standing at a login screen watching
    /// a tile not work, with nothing on screen to say why. Catching it at
    /// enrolment turns a mystery into a typo.
    /// </summary>
    public static void Store(string domain, string username, char[] password)
    {
        if (!Validate(domain, username, password))
            throw new UnauthorizedAccessException(
                "Windows rejected that username and password, so nothing was stored.");

        // Length-prefixed rather than delimited: a password may contain any
        // character at all, including whichever separator looked safe.
        var domainBytes = Encoding.UTF8.GetBytes(domain);
        var userBytes = Encoding.UTF8.GetBytes(username);
        var passBytes = Encoding.Unicode.GetBytes(password);

        var plain = new byte[12 + domainBytes.Length + userBytes.Length + passBytes.Length];
        try
        {
            var at = 0;
            void Write(byte[] part)
            {
                BitConverter.TryWriteBytes(plain.AsSpan(at, 4), part.Length);
                part.CopyTo(plain, at + 4);
                at += 4 + part.Length;
            }
            Write(domainBytes);
            Write(userBytes);
            Write(passBytes);

            var protectedBlob = ProtectedData.Protect(plain, Entropy, DataProtectionScope.LocalMachine);
            Directory.CreateDirectory(Config.AgentConfig.Directory);
            File.WriteAllBytes(VaultPath, protectedBlob);

            // Locking the file down is part of storing it, not a flourish
            // afterwards. If it fails, the write is undone: a half-finished
            // enrolment that reports failure while leaving a readable sealed
            // password on disk is worse than one that stores nothing.
            try
            {
                Restrict(VaultPath);
            }
            catch
            {
                try { File.Delete(VaultPath); } catch { /* nothing better to do */ }
                throw;
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plain);
            CryptographicOperations.ZeroMemory(passBytes);
        }
    }

    /// <summary>Null when nothing is enrolled, or when the blob will not open.</summary>
    public static LogonCredential? Unseal()
    {
        if (!File.Exists(VaultPath)) return null;

        byte[] plain;
        try
        {
            plain = ProtectedData.Unprotect(File.ReadAllBytes(VaultPath), Entropy,
                                            DataProtectionScope.LocalMachine);
        }
        catch (CryptographicException)
        {
            // Wrong machine, or tampered with. Same posture as the device
            // secret: behave as though nothing is enrolled rather than throw
            // out of a service Windows would restart in a loop.
            return null;
        }

        try
        {
            var at = 0;
            byte[] Read()
            {
                if (at + 4 > plain.Length) throw new InvalidDataException("truncated");
                var len = BitConverter.ToInt32(plain, at);
                if (len < 0 || at + 4 + len > plain.Length)
                    throw new InvalidDataException("bad length prefix");
                var part = plain[(at + 4)..(at + 4 + len)];
                at += 4 + len;
                return part;
            }
            var domain = Encoding.UTF8.GetString(Read());
            var user = Encoding.UTF8.GetString(Read());
            return new LogonCredential(domain, user, Read());
        }
        catch (InvalidDataException)
        {
            return null;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plain);
        }
    }

    public static void Forget()
    {
        if (!File.Exists(VaultPath)) return;

        // Overwrite before unlinking. On a journalling filesystem or an SSD
        // that remaps its own blocks this is not a guarantee, but it costs one
        // write and closes the easy case.
        var length = (int)new FileInfo(VaultPath).Length;
        File.WriteAllBytes(VaultPath, RandomNumberGenerator.GetBytes(length));
        File.Delete(VaultPath);
    }

    /// <summary>SYSTEM and administrators, nobody else, no inheritance.</summary>
    private static void Restrict(string path)
    {
        var info = new FileInfo(path);
        var acl = info.GetAccessControl();
        acl.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);

        foreach (var sid in new[] { WellKnownSidType.LocalSystemSid,
                                    WellKnownSidType.BuiltinAdministratorsSid })
        {
            acl.AddAccessRule(new FileSystemAccessRule(
                new SecurityIdentifier(sid, null),
                FileSystemRights.FullControl, AccessControlType.Allow));
        }
        info.SetAccessControl(acl);
    }

    // ----------------------------------------------------------------- Win32

    private const int LOGON32_LOGON_INTERACTIVE = 2;
    private const int LOGON32_PROVIDER_DEFAULT = 0;

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool LogonUserW(string username, string? domain, IntPtr password,
                                          int logonType, int logonProvider, out IntPtr token);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    /// <summary>
    /// Ask Windows whether these really are the account's credentials.
    ///
    /// The password reaches Win32 through unmanaged memory we allocate and wipe
    /// ourselves. Marshalling a string would leave a copy on the managed heap
    /// that cannot be cleared and would surface in any crash dump taken later.
    /// </summary>
    public static bool Validate(string domain, string username, char[] password)
    {
        var bytes = (password.Length + 1) * sizeof(char);
        var ptr = Marshal.AllocHGlobal(bytes);
        try
        {
            Marshal.Copy(password, 0, ptr, password.Length);
            Marshal.WriteInt16(ptr, password.Length * sizeof(char), 0);

            if (!LogonUserW(username, string.IsNullOrEmpty(domain) ? null : domain,
                            ptr, LOGON32_LOGON_INTERACTIVE, LOGON32_PROVIDER_DEFAULT,
                            out var token))
                return false;

            CloseHandle(token);
            return true;
        }
        finally
        {
            for (var i = 0; i < bytes; i++) Marshal.WriteByte(ptr, i, 0);
            Marshal.FreeHGlobal(ptr);
        }
    }
}
