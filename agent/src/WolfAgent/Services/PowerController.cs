using System.Runtime.InteropServices;

namespace Wolf.Agent.Services;

/// <summary>
/// Graceful shutdown and restart. PRD §3 is emphatic that these go through
/// Windows and not through the relay: the ESP32 must never be used to force a
/// shutdown, because that is data loss dressed up as a feature.
/// </summary>
public sealed class PowerController(SessionMonitor sessions, ILogger<PowerController> log)
{
    /// <summary>
    /// Short countdown after delivery. The app asks users to save work before sending.
    /// </summary>
    private const uint GracePeriodSeconds = 5;

    public bool Shutdown(string reason, out string? error) =>
        Initiate(restart: false, reason, out error);

    public bool Restart(string reason, out string? error) =>
        Initiate(restart: true, reason, out error);

    private bool Initiate(bool restart, string reason, out string? error)
    {
        error = null;

        if (!EnableShutdownPrivilege(out var privError))
        {
            error = privError;
            log.LogError("cannot acquire SeShutdownPrivilege: {Error}", privError);
            return false;
        }

        // Tell the status reporter first. InitiateShutdown returns immediately
        // and Windows may stop this process before the next heartbeat, so if
        // we waited, the app would show LOGGED_IN right up until the agent
        // vanished and then flip to UNKNOWN — which looks like a crash.
        sessions.NoteTransition(
            restart ? WindowsState.RESTARTING : WindowsState.SHUTTING_DOWN,
            TimeSpan.FromMinutes(3));

        // FORCE_OTHERS is not optional here, however aggressive it sounds. This
        // service runs in session 0, so from the API's point of view the person
        // sitting at the keyboard is "another user", and without this flag
        // InitiateShutdown refuses outright with ERROR_SHUTDOWN_USERS_LOGGED_ON
        // (1191) on any machine that someone is actually logged in to — which
        // is every machine this feature exists for.
        //
        // These flags permit session-0 shutdown with users signed in.
        // Sessions may be forced closed, so the phone warns to save work first.
        uint flags = SHUTDOWN_FORCE_OTHERS | SHUTDOWN_FORCE_SELF | (restart
            ? SHUTDOWN_RESTART
            : SHUTDOWN_POWEROFF);

        var result = InitiateShutdownW(
            lpMachineName: null,
            lpMessage: $"WolfPC: {reason}",
            dwGracePeriod: GracePeriodSeconds,
            dwShutdownFlags: flags,
            dwReason: SHTDN_REASON_MAJOR_OTHER | SHTDN_REASON_MINOR_OTHER | SHTDN_REASON_FLAG_PLANNED);

        if (result != ERROR_SUCCESS)
        {
            error = $"InitiateShutdown failed with {result}";
            log.LogError("{Error}", error);
            sessions.NoteTransition(WindowsState.UNKNOWN, TimeSpan.Zero);
            return false;
        }

        log.LogInformation("{Action} initiated ({Grace}s grace): {Reason}",
            restart ? "restart" : "shutdown", GracePeriodSeconds, reason);
        return true;
    }

    /// <summary>Cancels a pending shutdown inside the grace period.</summary>
    public bool Abort()
    {
        var ok = AbortSystemShutdownW(null);
        if (ok) sessions.NoteTransition(WindowsState.UNKNOWN, TimeSpan.Zero);
        return ok;
    }

    // ------------------------------------------------------------- interop ----

    private const int ERROR_SUCCESS = 0;

    // Required from session 0 whenever anyone is logged on; see Initiate().
    private const uint SHUTDOWN_FORCE_OTHERS = 0x00000001;
    private const uint SHUTDOWN_RESTART = 0x00000004;
    private const uint SHUTDOWN_POWEROFF = 0x00000008;

    // A nonzero grace period requires FORCE_SELF; GRACE_OVERRIDE instead
    // means immediate shutdown and must not be combined with this countdown.
    private const uint SHUTDOWN_FORCE_SELF = 0x00000002;

    private const uint SHTDN_REASON_MAJOR_OTHER = 0x00000000;
    private const uint SHTDN_REASON_MINOR_OTHER = 0x00000000;
    private const uint SHTDN_REASON_FLAG_PLANNED = 0x80000000;

    private const int SE_PRIVILEGE_ENABLED = 0x00000002;
    private const int TOKEN_ADJUST_PRIVILEGES = 0x0020;
    private const int TOKEN_QUERY = 0x0008;

    [StructLayout(LayoutKind.Sequential)]
    private struct LUID { public uint LowPart; public int HighPart; }

    [StructLayout(LayoutKind.Sequential)]
    private struct TOKEN_PRIVILEGES
    {
        public int PrivilegeCount;
        public LUID Luid;
        public int Attributes;
    }

    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern int InitiateShutdownW(
        string? lpMachineName, string? lpMessage, uint dwGracePeriod,
        uint dwShutdownFlags, uint dwReason);

    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AbortSystemShutdownW(string? lpMachineName);

    [DllImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool OpenProcessToken(IntPtr processHandle, int desiredAccess,
                                                out IntPtr tokenHandle);

    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool LookupPrivilegeValueW(string? system, string name, out LUID luid);

    [DllImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AdjustTokenPrivileges(
        IntPtr tokenHandle, [MarshalAs(UnmanagedType.Bool)] bool disableAll,
        ref TOKEN_PRIVILEGES newState, int bufferLength, IntPtr previousState, IntPtr returnLength);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    /// <summary>
    /// LocalSystem holds SeShutdownPrivilege but it is disabled by default, as
    /// every privilege is. Enabling it per call keeps the window in which this
    /// process can shut the machine down as narrow as possible.
    /// </summary>
    private static bool EnableShutdownPrivilege(out string? error)
    {
        error = null;
        if (!OpenProcessToken(GetCurrentProcess(),
                              TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, out var token))
        {
            error = $"OpenProcessToken failed ({Marshal.GetLastWin32Error()})";
            return false;
        }
        try
        {
            if (!LookupPrivilegeValueW(null, "SeShutdownPrivilege", out var luid))
            {
                error = $"LookupPrivilegeValue failed ({Marshal.GetLastWin32Error()})";
                return false;
            }

            var privileges = new TOKEN_PRIVILEGES
            {
                PrivilegeCount = 1,
                Luid = luid,
                Attributes = SE_PRIVILEGE_ENABLED,
            };

            if (!AdjustTokenPrivileges(token, false, ref privileges, 0, IntPtr.Zero, IntPtr.Zero))
            {
                error = $"AdjustTokenPrivileges failed ({Marshal.GetLastWin32Error()})";
                return false;
            }

            // AdjustTokenPrivileges reports success even when it changed
            // nothing, so the real answer is in GetLastError.
            var last = Marshal.GetLastWin32Error();
            if (last != ERROR_SUCCESS)
            {
                error = $"shutdown privilege not held ({last})";
                return false;
            }
            return true;
        }
        finally
        {
            CloseHandle(token);
        }
    }
}
