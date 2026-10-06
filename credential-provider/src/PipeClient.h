#pragma once

#include <windows.h>
#include <string>

/**
 * The provider's end of \\.\pipe\WolfPC.Login.
 *
 * Every call here has a timeout, without exception. LogonUI draws the login
 * screen on a single thread, and a credential provider that blocks takes the
 * whole screen with it - no tiles, no password box, nothing to do but hold the
 * power button. A timeout that fires is a tile that says it could not reach the
 * service, which is recoverable; a wait that never returns is not.
 *
 * All I/O is overlapped for that reason rather than for throughput. Plain
 * ReadFile on a named pipe blocks until the far end answers, and "the service
 * is wedged" is exactly the case this has to survive.
 */
class PipeClient
{
public:
    PipeClient() = default;
    ~PipeClient();

    PipeClient(const PipeClient&) = delete;
    PipeClient& operator=(const PipeClient&) = delete;

    bool Connect(DWORD timeoutMs = 3000);
    void Close();
    bool IsConnected() const { return _pipe != INVALID_HANDLE_VALUE; }

    /** Send one line, read one line. False on timeout, disconnection or error. */
    bool Ask(const std::string& request, std::string& reply, DWORD timeoutMs = 5000);

private:
    bool WriteLine(const std::string& line, DWORD timeoutMs);
    bool ReadLine(std::string& line, DWORD timeoutMs);

    HANDLE _pipe = INVALID_HANDLE_VALUE;
    std::string _pending;   // bytes read past the end of the last line
};
