using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Wolf.Agent.Config;

/// <summary>
/// Agent configuration, kept in %ProgramData%\WolfPC.
///
/// The device secret is the one value that must not sit in a readable file, so
/// it is sealed with DPAPI at <see cref="DataProtectionScope.LocalMachine"/>.
/// Machine scope rather than user scope is not a shortcut: the service runs as
/// LocalSystem and has to start before anybody logs in, so there is no user
/// profile to protect it with. What that buys is that the ciphertext is useless
/// on any other machine — copying the file to another PC gets you nothing —
/// while an administrator on this machine could still unseal it. An admin on
/// the box could also just press the power button, so that is not the boundary
/// we are defending.
/// </summary>
public sealed class AgentConfig
{
    public string DeviceId { get; set; } = "";
    public string PcId { get; set; } = "";
    public string SupabaseUrl { get; set; } = "";
    public string SupabaseAnonKey { get; set; } = "";
    public int LocalApiPort { get; set; } = 8443;
    public int PollIntervalMs { get; set; } = 3000;
    public int HeartbeatIntervalMs { get; set; } = 10000;
    public bool LocalApiEnabled { get; set; } = true;

    private byte[]? _secret;

    public static string Directory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "WolfPC");

    public static string ConfigPath => Path.Combine(Directory, "agent.json");
    private static string SecretPath => Path.Combine(Directory, "device.secret");
    public static string CertPath => Path.Combine(Directory, "lan.pfx");

    public bool IsProvisioned => DeviceId.Length > 0 && PcId.Length > 0
                                 && SupabaseUrl.Length > 0 && _secret is { Length: 32 };

    public static AgentConfig Load()
    {
        System.IO.Directory.CreateDirectory(Directory);

        var config = File.Exists(ConfigPath)
            ? JsonSerializer.Deserialize<AgentConfig>(File.ReadAllText(ConfigPath)) ?? new AgentConfig()
            : new AgentConfig();

        config._secret = ReadSecret();
        return config;
    }

    public void Save()
    {
        System.IO.Directory.CreateDirectory(Directory);
        File.WriteAllText(ConfigPath, JsonSerializer.Serialize(this, JsonOpts));
    }

    private static readonly JsonSerializerOptions JsonOpts = new() { WriteIndented = true };

    /// <summary>Returns a copy; callers should clear it when done.</summary>
    public byte[] Secret()
    {
        if (_secret is null) throw new InvalidOperationException("no device secret is stored");
        return (byte[])_secret.Clone();
    }

    public void SetSecretFromHex(string hex)
    {
        var bytes = Convert.FromHexString(hex.Trim());
        if (bytes.Length != 32)
            throw new ArgumentException("device secret must be 64 hex characters (32 bytes)");

        System.IO.Directory.CreateDirectory(Directory);
        var sealed_ = ProtectedData.Protect(bytes, Entropy, DataProtectionScope.LocalMachine);
        File.WriteAllBytes(SecretPath, sealed_);
        _secret = bytes;
    }

    private static byte[] ReadSecret()
    {
        if (!File.Exists(SecretPath)) return Array.Empty<byte>();
        try
        {
            return ProtectedData.Unprotect(File.ReadAllBytes(SecretPath), Entropy,
                                           DataProtectionScope.LocalMachine);
        }
        catch (CryptographicException)
        {
            // Wrong machine, or the file was tampered with. Treat as
            // unprovisioned rather than crashing a service that Windows will
            // then restart in a loop.
            return Array.Empty<byte>();
        }
    }

    // Extra entropy so that an unrelated DPAPI blob on this machine cannot be
    // swapped in for ours.
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("WolfPC/device-secret/v1");
}
