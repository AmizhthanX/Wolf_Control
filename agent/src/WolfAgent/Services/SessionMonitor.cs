using System.Runtime.InteropServices;

namespace Wolf.Agent.Services;

public enum WindowsState { OFF, BOOTING, LOGIN_SCREEN, LOGGED_IN, SHUTTING_DOWN, RESTARTING, UNKNOWN }
public enum SessionState { NONE, LOCKED, ACTIVE }

/// <summary>
/// Answers the question the ESP32 cannot: is Windows at the login screen, or
/// logged in, or locked? (PRD §28)
///
/// Everything here is read from the console session through the Terminal
/// Services API, which is one of the few things a LocalSystem service can ask
/// about the interactive desktop without having a session of its own.
/// </summary>
public sealed class SessionMonitor(ILogger<SessionMonitor> log)
{
    // Set by PowerController when it initiates something, because no API will
    // tell us "a shutdown is in progress" before the process is torn down.
    private WindowsState? _pendingTransition;
    private DateTimeOffset _pendingUntil;

    /// <summary>
    /// How long after power-on "no console session yet" is read as BOOTING
    /// rather than UNKNOWN. It only ever applies when there is no session to
    /// inspect, so it can never override something directly observable.
    /// </summary>
    private static readonly TimeSpan BootWindow = TimeSpan.FromSeconds(120);

    public void NoteTransition(WindowsState state, TimeSpan validFor)
    {
        _pendingTransition = state;
        _pendingUntil = DateTimeOffset.UtcNow + validFor;
    }

    public (WindowsState Windows, SessionState Session, string? UserName) Read()
    {
        if (_pendingTransition is { } pending)
        {
            if (DateTimeOffset.UtcNow < _pendingUntil) return (pending, SessionState.NONE, null);
            _pendingTransition = null;
        }

        try
        {
            var sessionId = WTSGetActiveConsoleSessionId();
            if (sessionId == 0xFFFFFFFF)
            {
                // No console session attached at all. Early in boot that means
                // the machine is still coming up; at any other time it means a
                // session switch or a shutdown in progress, and we genuinely
                // do not know.
                return (Environment.TickCount64 < BootWindow.TotalMilliseconds
                            ? WindowsState.BOOTING
                            : WindowsState.UNKNOWN,
                        SessionState.NONE, null);
            }

            var userName = QueryString(sessionId, WTS_INFO_CLASS.WTSUserName);
            var flags = QuerySessionFlags(sessionId);

            // LogonUI owns the console session with no user name attached.
            // That *is* the login screen, whatever the clock says — and it is
            // the state the user is watching for after a remote power on.
            //
            // This used to be gated on uptime, reporting BOOTING for the first
            // 90 seconds. That was a straightforward lie: the login screen
            // routinely appears well inside that window, so the app showed
            // "Booting" while the user was looking at the login prompt, and
            // only told the truth a minute later. A state this code can observe
            // directly should never be overridden by a guess about timing.
            if (string.IsNullOrEmpty(userName))
            {
                return (WindowsState.LOGIN_SCREEN, SessionState.NONE, null);
            }

            var session = flags switch
            {
                WTS_SESSIONSTATE_LOCK => SessionState.LOCKED,
                WTS_SESSIONSTATE_UNLOCK => SessionState.ACTIVE,
                _ => SessionState.ACTIVE,
            };

            return (WindowsState.LOGGED_IN, session, userName);
        }
        catch (Exception ex)
        {
            log.LogWarning("session query failed: {Message}", ex.Message);
            return (WindowsState.UNKNOWN, SessionState.NONE, null);
        }
    }

    // ------------------------------------------------------------- interop ----

    private const int WTS_SESSIONSTATE_LOCK = 0;
    private const int WTS_SESSIONSTATE_UNLOCK = 1;
    private static readonly IntPtr WTS_CURRENT_SERVER_HANDLE = IntPtr.Zero;

    private enum WTS_INFO_CLASS
    {
        WTSUserName = 5,
        WTSSessionInfoEx = 25,
    }

    [DllImport("kernel32.dll")]
    private static extern uint WTSGetActiveConsoleSessionId();

    [DllImport("wtsapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool WTSQuerySessionInformationW(
        IntPtr hServer, uint sessionId, WTS_INFO_CLASS infoClass,
        out IntPtr ppBuffer, out uint pBytesReturned);

    [DllImport("wtsapi32.dll")]
    private static extern void WTSFreeMemory(IntPtr pMemory);

    private static string QueryString(uint sessionId, WTS_INFO_CLASS infoClass)
    {
        if (!WTSQuerySessionInformationW(WTS_CURRENT_SERVER_HANDLE, sessionId, infoClass,
                                         out var buffer, out _))
            return "";
        try
        {
            return Marshal.PtrToStringUni(buffer) ?? "";
        }
        finally
        {
            WTSFreeMemory(buffer);
        }
    }

    /// <summary>
    /// Reads WTSINFOEX_LEVEL1.SessionFlags, which is the only reliable way for
    /// a service to tell "locked" from "signed in and using the machine".
    ///
    /// The offsets are read by hand rather than through a [StructLayout] type
    /// because WTSINFOEXW is a DWORD followed by a union whose members contain
    /// LARGE_INTEGERs. That forces 8-byte alignment on x64, so the payload
    /// starts at offset 8, not 4 — a declared struct gets this wrong unless the
    /// padding is spelled out anyway.
    /// </summary>
    private static int QuerySessionFlags(uint sessionId)
    {
        if (!WTSQuerySessionInformationW(WTS_CURRENT_SERVER_HANDLE, sessionId,
                                         WTS_INFO_CLASS.WTSSessionInfoEx,
                                         out var buffer, out var size))
            return -1;
        try
        {
            if (size < 20) return -1;
            var level = Marshal.ReadInt32(buffer, 0);
            if (level != 1) return -1;
            //  0  Level
            //  4  (padding)
            //  8  SessionId
            // 12  SessionState
            // 16  SessionFlags
            return Marshal.ReadInt32(buffer, 16);
        }
        finally
        {
            WTSFreeMemory(buffer);
        }
    }
}
