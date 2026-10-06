#include "PipeClient.h"

namespace
{
    constexpr wchar_t kPipeName[] = L"\\\\.\\pipe\\WolfPC.Login";

    /**
     * Wait for one overlapped operation, cancelling it if the deadline passes.
     *
     * The cancel matters. Returning from this function while the kernel still
     * owns the OVERLAPPED and its buffer - both of which live on our stack -
     * would let it write into a frame that no longer exists the moment the
     * service eventually answers. CancelIoEx followed by a blocking
     * GetOverlappedResult is what makes the wait safe to abandon.
     */
    bool AwaitOverlapped(HANDLE pipe, OVERLAPPED& overlapped, DWORD timeoutMs, DWORD& transferred)
    {
        if (WaitForSingleObject(overlapped.hEvent, timeoutMs) == WAIT_OBJECT_0)
            return GetOverlappedResult(pipe, &overlapped, &transferred, FALSE) != FALSE;

        CancelIoEx(pipe, &overlapped);
        GetOverlappedResult(pipe, &overlapped, &transferred, TRUE);
        return false;
    }

    struct Event
    {
        HANDLE handle = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        ~Event() { if (handle) CloseHandle(handle); }
    };
}

PipeClient::~PipeClient()
{
    Close();
}

void PipeClient::Close()
{
    if (_pipe != INVALID_HANDLE_VALUE)
    {
        CloseHandle(_pipe);
        _pipe = INVALID_HANDLE_VALUE;
    }
    _pending.clear();
}

bool PipeClient::Connect(DWORD timeoutMs)
{
    Close();

    const ULONGLONG deadline = GetTickCount64() + timeoutMs;

    for (;;)
    {
        _pipe = CreateFileW(kPipeName, GENERIC_READ | GENERIC_WRITE, 0, nullptr,
                            OPEN_EXISTING, FILE_FLAG_OVERLAPPED, nullptr);

        if (_pipe != INVALID_HANDLE_VALUE) break;

        // ERROR_PIPE_BUSY means the single server instance is serving somebody
        // else. Anything else - including the service not running at all -
        // is not worth waiting on.
        if (GetLastError() != ERROR_PIPE_BUSY) return false;
        if (GetTickCount64() >= deadline) return false;

        if (!WaitNamedPipeW(kPipeName, 200) && GetTickCount64() >= deadline)
            return false;
    }

    DWORD mode = PIPE_READMODE_BYTE;
    SetNamedPipeHandleState(_pipe, &mode, nullptr, nullptr);
    return true;
}

bool PipeClient::WriteLine(const std::string& line, DWORD timeoutMs)
{
    if (_pipe == INVALID_HANDLE_VALUE) return false;

    const std::string payload = line + "\n";

    Event event;
    if (!event.handle) return false;

    OVERLAPPED overlapped{};
    overlapped.hEvent = event.handle;

    DWORD written = 0;
    if (WriteFile(_pipe, payload.data(), static_cast<DWORD>(payload.size()),
                  &written, &overlapped))
        return written == payload.size();

    if (GetLastError() != ERROR_IO_PENDING) return false;

    return AwaitOverlapped(_pipe, overlapped, timeoutMs, written)
        && written == payload.size();
}

bool PipeClient::ReadLine(std::string& line, DWORD timeoutMs)
{
    if (_pipe == INVALID_HANDLE_VALUE) return false;

    const ULONGLONG deadline = GetTickCount64() + timeoutMs;

    for (;;)
    {
        // The service writes one line per reply, but a byte-mode pipe makes no
        // promise about where a read lands. Anything past the newline belongs
        // to the next reply and is kept.
        const size_t newline = _pending.find('\n');
        if (newline != std::string::npos)
        {
            line = _pending.substr(0, newline);
            _pending.erase(0, newline + 1);
            if (!line.empty() && line.back() == '\r') line.pop_back();

            // Tolerate a leading byte order mark. The agent no longer emits
            // one, but it did, and the symptom was not obvious for a moment:
            // the handshake simply did not match, the client hung up, and the
            // pair reconnected every three seconds forever. .NET clients never
            // saw it because StreamReader strips BOMs by default, so the
            // console harness passed while the real provider could not connect.
            // Two lines here make that class of mismatch impossible again.
            if (line.size() >= 3 &&
                static_cast<unsigned char>(line[0]) == 0xEF &&
                static_cast<unsigned char>(line[1]) == 0xBB &&
                static_cast<unsigned char>(line[2]) == 0xBF)
                line.erase(0, 3);

            return true;
        }

        const ULONGLONG now = GetTickCount64();
        if (now >= deadline) return false;

        Event event;
        if (!event.handle) return false;

        OVERLAPPED overlapped{};
        overlapped.hEvent = event.handle;

        char buffer[1024];
        DWORD read = 0;

        if (!ReadFile(_pipe, buffer, sizeof buffer, &read, &overlapped))
        {
            if (GetLastError() != ERROR_IO_PENDING) return false;
            if (!AwaitOverlapped(_pipe, overlapped, static_cast<DWORD>(deadline - now), read))
                return false;
        }

        if (read == 0) return false;   // the far end closed
        _pending.append(buffer, read);
    }
}

bool PipeClient::Ask(const std::string& request, std::string& reply, DWORD timeoutMs)
{
    return WriteLine(request, timeoutMs) && ReadLine(reply, timeoutMs);
}
