using System.Runtime.InteropServices;
using System.Text;

namespace Wolf.Agent.Services;

/// <summary>LockWorkStation must execute on the interactive user's desktop, not service session 0.</summary>
public sealed class SessionLocker(SessionMonitor sessions, ILogger<SessionLocker> log)
{
    public bool Lock(out string? error)
    {
        error = null;
        var state = sessions.Read();
        if (state.Session == SessionState.LOCKED || state.Windows == WindowsState.LOGIN_SCREEN)
            return true;
        var session = WTSGetActiveConsoleSessionId();
        if (session == uint.MaxValue || string.IsNullOrEmpty(state.UserName))
        {
            error = "No signed-in console user to lock.";
            return false;
        }
        if (!WTSQueryUserToken(session, out var token))
        {
            error = $"Could not access the console user ({Marshal.GetLastWin32Error()}).";
            return false;
        }
        try
        {
            var exe = Environment.ProcessPath!;
            var startup = new StartupInfo
            {
                cb = Marshal.SizeOf<StartupInfo>(),
                lpDesktop = @"winsta0\default",
            };
            if (!CreateProcessAsUserW(token, exe, new StringBuilder($"\"{exe}\" session-lock"),
                    IntPtr.Zero, IntPtr.Zero, false, 0x08000000, IntPtr.Zero,
                    AppContext.BaseDirectory, ref startup, out var process))
            {
                error = $"Could not start the lock helper ({Marshal.GetLastWin32Error()}).";
                return false;
            }
            try
            {
                if (WaitForSingleObject(process.hProcess, 10_000) != 0 ||
                    !GetExitCodeProcess(process.hProcess, out var result) || result != 0)
                {
                    error = "The desktop lock helper did not complete successfully.";
                    return false;
                }
                log.LogInformation("lock requested for console session {Session}", session);
                return true;
            }
            finally { CloseHandle(process.hThread); CloseHandle(process.hProcess); }
        }
        finally { CloseHandle(token); }
    }

    // Internal helper mode: runs as the existing user, with no configuration/network access.
    public static int LockCurrentDesktop() => LockWorkStation() ? 0 : 1;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct StartupInfo
    {
        public int cb;
        public string? lpReserved, lpDesktop, lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInfo
    {
        public IntPtr hProcess, hThread;
        public uint dwProcessId, dwThreadId;
    }
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool LockWorkStation();
    [DllImport("kernel32.dll")] private static extern uint WTSGetActiveConsoleSessionId();
    [DllImport("wtsapi32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool WTSQueryUserToken(uint sessionId, out IntPtr token);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreateProcessAsUserW(IntPtr token, string applicationName,
        StringBuilder commandLine, IntPtr processAttributes, IntPtr threadAttributes,
        [MarshalAs(UnmanagedType.Bool)] bool inheritHandles, uint creationFlags,
        IntPtr environment, string directory, ref StartupInfo startup, out ProcessInfo process);
    [DllImport("kernel32.dll")] private static extern uint WaitForSingleObject(IntPtr handle, uint ms);
    [DllImport("kernel32.dll")] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll")] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);
}
