using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using QRCoder;
using Wolf.Agent.Config;
using Wolf.Agent.LocalApi;

namespace Wolf.Agent.Pairing;

/// <summary>
/// The pairing QR (PRD §17, step 4).
///
/// The code alone would be enough to pair. The QR carries the rest of the setup
/// with it — project URL, anon key, pc id, and crucially the LAN address and
/// certificate fingerprint — so the phone is fully configured by one scan
/// instead of the user copying a 64-character hex string by hand. Typing that
/// wrong is the most likely way the offline path silently never works.
///
/// Nothing in the payload is a secret worth protecting beyond the code's ten
/// minute life: the anon key is public, the fingerprint is a hash of a
/// certificate the agent hands to anyone who connects, and the code is single
/// use and still has to be redeemed by an account that already owns the pc.
/// </summary>
public static class PairingQr
{
    public const string Scheme = "wolfpc";

    public static string BuildPayload(AgentConfig config, string code, string? fingerprint)
    {
        var query = new StringBuilder();

        void Add(string key, string? value)
        {
            if (string.IsNullOrEmpty(value)) return;
            if (query.Length > 0) query.Append('&');
            query.Append(key).Append('=').Append(WebUtility.UrlEncode(value));
        }

        // v lets a future app reject a payload it does not understand rather
        // than half-applying it.
        Add("v", "1");
        Add("url", config.SupabaseUrl);
        Add("key", config.SupabaseAnonKey);
        Add("pc", config.PcId);
        Add("code", code);
        Add("ctrl", "wolfpc.local");

        var host = LocalIPv4();
        if (host is not null) Add("host", $"{host}:{config.LocalApiPort}");
        Add("fp", fingerprint);

        return $"{Scheme}://pair?{query}";
    }

    public static string? Fingerprint()
    {
        if (!File.Exists(AgentConfig.CertPath)) return null;
        try
        {
            var certificate = X509CertificateLoader.LoadPkcs12FromFile(AgentConfig.CertPath, null);
            return LanCertificate.Fingerprint(certificate);
        }
        catch
        {
            // A missing or unreadable certificate just means the QR carries no
            // LAN details; pairing itself still works.
            return null;
        }
    }

    /// <summary>
    /// The address the phone should dial on the home network.
    ///
    /// Picks a private IPv4 on an interface that is actually up, skipping
    /// loopback, tunnels and the virtual adapters that Hyper-V, WSL and VPN
    /// clients leave behind — those are the ones that look plausible and are
    /// never reachable from a phone.
    /// </summary>
    public static string? LocalIPv4()
    {
        var candidates = new List<(int Rank, string Address)>();

        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                continue;

            var name = nic.Name + " " + nic.Description;
            var virtualish = name.Contains("virtual", StringComparison.OrdinalIgnoreCase)
                          || name.Contains("hyper-v", StringComparison.OrdinalIgnoreCase)
                          || name.Contains("vethernet", StringComparison.OrdinalIgnoreCase)
                          || name.Contains("wsl", StringComparison.OrdinalIgnoreCase)
                          || name.Contains("vpn", StringComparison.OrdinalIgnoreCase)
                          || name.Contains("tailscale", StringComparison.OrdinalIgnoreCase);

            foreach (var ip in nic.GetIPProperties().UnicastAddresses)
            {
                if (ip.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var bytes = ip.Address.GetAddressBytes();
                if (bytes[0] == 127 || bytes[0] == 169) continue;

                var isPrivate = bytes[0] == 10
                    || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31)
                    || (bytes[0] == 192 && bytes[1] == 168);
                if (!isPrivate) continue;

                // Wired first: this PC is on Ethernet by design (PRD).
                var rank = virtualish ? 3
                    : nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet ? 0
                    : nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 1
                    : 2;

                candidates.Add((rank, ip.Address.ToString()));
            }
        }

        return candidates.OrderBy(c => c.Rank).Select(c => c.Address).FirstOrDefault();
    }

    /// <summary>
    /// Render the QR into the console using ANSI background colours.
    ///
    /// Drawing blocks in the terminal's foreground colour would produce light
    /// modules on a dark background in any dark-themed terminal — an inverted
    /// QR, which many scanners including ML Kit will not read. Setting an
    /// explicit white and black background instead means the code looks right
    /// whatever theme the terminal uses.
    ///
    /// Two spaces per module keeps it roughly square in a monospace font.
    ///
    /// QRCoder's ModuleMatrix already carries the standard 4-module quiet zone,
    /// so the 2 added here are extra margin for terminals that clip the last
    /// column or draw a border. More quiet zone is never a problem.
    /// </summary>
    public static string RenderAnsi(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);

        const string white = "[107m  [0m";
        const string black = "[40m  [0m";
        const int quiet = 2;

        var matrix = data.ModuleMatrix;
        var size = matrix.Count;
        var builder = new StringBuilder();

        void BlankRow()
        {
            for (var i = 0; i < size + quiet * 2; i++) builder.Append(white);
            builder.AppendLine();
        }

        for (var i = 0; i < quiet; i++) BlankRow();

        for (var y = 0; y < size; y++)
        {
            for (var i = 0; i < quiet; i++) builder.Append(white);
            for (var x = 0; x < size; x++) builder.Append(matrix[y][x] ? black : white);
            for (var i = 0; i < quiet; i++) builder.Append(white);
            builder.AppendLine();
        }

        for (var i = 0; i < quiet; i++) BlankRow();

        return builder.ToString();
    }

    /// <summary>A PNG fallback, for when the terminal mangles the block drawing.</summary>
    public static string WritePng(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var bytes = new PngByteQRCode(data).GetGraphic(10);

        Directory.CreateDirectory(AgentConfig.Directory);
        var path = Path.Combine(AgentConfig.Directory, "pairing.png");
        File.WriteAllBytes(path, bytes);
        return path;
    }

    /// <summary>
    /// Best effort: the pairing window is short, so opening the image saves the
    /// user hunting for it. A machine with no image association simply gets the
    /// path printed instead.
    /// </summary>
    public static void TryOpen(string path)
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = path,
                UseShellExecute = true,
            });
        }
        catch
        {
            // Ignored: the path is printed either way.
        }
    }
}
