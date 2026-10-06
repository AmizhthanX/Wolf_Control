#include "Credential.h"
#include "Provider.h"
#include "guid.h"

// SECURITY_WIN32 is what makes security.h expose the package names; without it
// the header compiles to almost nothing and NEGOSSP_NAME_A is undefined.
#define SECURITY_WIN32

#include <ntsecapi.h>
#include <security.h>
#include <wincred.h>
#include <wincrypt.h>
#include <intsafe.h>
#include <chrono>
#include <vector>
#include <sddl.h>

// Spelled out rather than pulled in from ntstatus.h. That header redefines a
// long list of codes winnt.h has already declared, and the usual fix -
// WIN32_NO_STATUS before windows.h, then undefining it - has to be applied in
// every translation unit that reaches this one. Two constants are not worth
// that, and these values are fixed for the lifetime of the platform.
#ifndef STATUS_SUCCESS
#define STATUS_SUCCESS       ((NTSTATUS)0x00000000L)
#endif
#ifndef STATUS_LOGON_FAILURE
#define STATUS_LOGON_FAILURE ((NTSTATUS)0xC000006DL)
#endif

namespace
{
    /** CoTaskMemAlloc a copy, the way every out-parameter here wants it. */
    HRESULT CopyOut(const std::wstring& source, wchar_t** destination)
    {
        const size_t bytes = (source.size() + 1) * sizeof(wchar_t);
        auto* copy = static_cast<wchar_t*>(CoTaskMemAlloc(bytes));
        if (!copy) return E_OUTOFMEMORY;
        memcpy(copy, source.c_str(), bytes);
        *destination = copy;
        return S_OK;
    }

    std::vector<BYTE> Base64Decode(const std::string& text)
    {
        DWORD size = 0;
        if (!CryptStringToBinaryA(text.c_str(), static_cast<DWORD>(text.size()),
                                  CRYPT_STRING_BASE64, nullptr, &size, nullptr, nullptr))
            return {};

        std::vector<BYTE> out(size);
        if (!CryptStringToBinaryA(text.c_str(), static_cast<DWORD>(text.size()),
                                  CRYPT_STRING_BASE64, out.data(), &size, nullptr, nullptr))
            return {};

        out.resize(size);
        return out;
    }

    std::wstring Utf8ToWide(const BYTE* bytes, size_t length)
    {
        if (length == 0) return {};
        const int needed = MultiByteToWideChar(CP_UTF8, 0,
            reinterpret_cast<const char*>(bytes), static_cast<int>(length), nullptr, 0);
        if (needed <= 0) return {};

        std::wstring out(static_cast<size_t>(needed), L'\0');
        MultiByteToWideChar(CP_UTF8, 0, reinterpret_cast<const char*>(bytes),
                            static_cast<int>(length), out.data(), needed);
        return out;
    }

    /** Split on single spaces. The protocol base64s anything that could contain one. */
    std::vector<std::string> Split(const std::string& line)
    {
        std::vector<std::string> parts;
        size_t start = 0;
        for (;;)
        {
            const size_t space = line.find(' ', start);
            if (space == std::string::npos) { parts.push_back(line.substr(start)); break; }
            parts.push_back(line.substr(start, space - start));
            start = space + 1;
        }
        return parts;
    }

    void InitUnicodeString(UNICODE_STRING& target, const std::wstring& source)
    {
        target.Length = static_cast<USHORT>(source.size() * sizeof(wchar_t));
        target.MaximumLength = target.Length;
        target.Buffer = const_cast<wchar_t*>(source.c_str());
    }

    /**
     * Ask LSA for the authentication package to submit against.
     *
     * Negotiate rather than MSV1_0: it resolves to MSV1_0 on a machine with
     * local accounts and to Kerberos on a domain, which costs nothing here and
     * is the difference between this working on a domain-joined PC and not.
     */
    HRESULT NegotiatePackage(ULONG* package)
    {
        HANDLE lsa = nullptr;
        NTSTATUS status = LsaConnectUntrusted(&lsa);
        if (status != STATUS_SUCCESS) return HRESULT_FROM_NT(status);

        LSA_STRING name{};
        char negotiate[] = NEGOSSP_NAME_A;
        name.Buffer = negotiate;
        name.Length = static_cast<USHORT>(strlen(negotiate));
        name.MaximumLength = static_cast<USHORT>(name.Length + 1);

        status = LsaLookupAuthenticationPackage(lsa, &name, package);
        LsaDeregisterLogonProcess(lsa);

        return status == STATUS_SUCCESS ? S_OK : HRESULT_FROM_NT(status);
    }

    void SecureWipe(std::wstring& text)
    {
        if (!text.empty())
            SecureZeroMemory(text.data(), text.size() * sizeof(wchar_t));
        text.clear();
        text.shrink_to_fit();
    }
}

WolfCredential::~WolfCredential()
{
    StopWorker();
    Wipe();
    if (_events) { _events->Release(); _events = nullptr; }
}

HRESULT WolfCredential::Initialize(CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario,
                                   WolfProvider* provider,
                                   const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR* descriptors)
{
    _scenario = scenario;
    _provider = provider;
    _descriptors = descriptors;

    // Start listening straight away, not on selection. An app-initiated unlock
    // arrives with nobody at the keyboard, so there is no selection to wait
    // for - and holding the pipe open is what tells the agent a provider
    // exists at all.
    StartWorker();
    return S_OK;
}

// ------------------------------------------------------------------ IUnknown

IFACEMETHODIMP_(ULONG) WolfCredential::AddRef()
{
    return static_cast<ULONG>(InterlockedIncrement(&_refs));
}

IFACEMETHODIMP_(ULONG) WolfCredential::Release()
{
    const long remaining = InterlockedDecrement(&_refs);
    if (remaining == 0) delete this;
    return static_cast<ULONG>(remaining);
}

IFACEMETHODIMP WolfCredential::QueryInterface(REFIID riid, void** ppv)
{
    if (!ppv) return E_POINTER;

    if (riid == IID_IUnknown ||
        riid == IID_ICredentialProviderCredential ||
        riid == IID_ICredentialProviderCredential2)
    {
        *ppv = static_cast<ICredentialProviderCredential2*>(this);
        AddRef();
        return S_OK;
    }

    *ppv = nullptr;
    return E_NOINTERFACE;
}

// ------------------------------------------------------ selection lifecycle

IFACEMETHODIMP WolfCredential::Advise(ICredentialProviderCredentialEvents* events)
{
    if (_events) _events->Release();
    _events = events;
    if (_events) _events->AddRef();
    return S_OK;
}

IFACEMETHODIMP WolfCredential::UnAdvise()
{
    if (_events) { _events->Release(); _events = nullptr; }
    return S_OK;
}

IFACEMETHODIMP WolfCredential::SetSelected(BOOL* autoLogon)
{
    // Selecting must not start a challenge. Someone may be arrowing through
    // the tiles, and firing one here would buzz their phone every time they
    // passed this one - and burn the single open challenge the agent allows.
    *autoLogon = FALSE;
    _selected.store(true);
    return S_OK;
}

IFACEMETHODIMP WolfCredential::SetDeselected()
{
    // Clearing the flag is what abandons an in-flight challenge; the worker
    // notices and cancels it server-side. The worker itself keeps running,
    // because an unlock sent from the app has to be heard whether or not this
    // tile happens to have focus.
    _selected.store(false);
    _submit.store(false);
    Wipe();
    _ready.store(false);
    return S_OK;
}

// ----------------------------------------------------------------- fields

IFACEMETHODIMP WolfCredential::GetFieldState(
    DWORD field,
    CREDENTIAL_PROVIDER_FIELD_STATE* state,
    CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* interactive)
{
    if (field >= WFIELD_COUNT || !state || !interactive) return E_INVALIDARG;

    *state = CPFS_DISPLAY_IN_SELECTED_TILE;
    *interactive = CPFIS_NONE;

    if (field == WFIELD_LABEL) *state = CPFS_DISPLAY_IN_BOTH;
    return S_OK;
}

IFACEMETHODIMP WolfCredential::GetStringValue(DWORD field, wchar_t** value)
{
    if (field >= WFIELD_COUNT || !value) return E_INVALIDARG;

    switch (field)
    {
    case WFIELD_LABEL:
        return CopyOut(L"Sign in with your phone", value);

    case WFIELD_STATUS:
    {
        std::lock_guard<std::mutex> lock(_statusGuard);
        return CopyOut(_status, value);
    }

    default:
        return CopyOut(L"", value);
    }
}

IFACEMETHODIMP WolfCredential::GetSubmitButtonValue(DWORD field, DWORD* adjacentTo)
{
    if (field != WFIELD_SUBMIT || !adjacentTo) return E_INVALIDARG;
    *adjacentTo = WFIELD_STATUS;
    return S_OK;
}

IFACEMETHODIMP WolfCredential::GetBitmapValue(DWORD, HBITMAP*)            { return E_NOTIMPL; }
IFACEMETHODIMP WolfCredential::GetCheckboxValue(DWORD, BOOL*, wchar_t**)  { return E_NOTIMPL; }
IFACEMETHODIMP WolfCredential::GetComboBoxValueCount(DWORD, DWORD*, DWORD*) { return E_NOTIMPL; }
IFACEMETHODIMP WolfCredential::GetComboBoxValueAt(DWORD, DWORD, wchar_t**)  { return E_NOTIMPL; }
IFACEMETHODIMP WolfCredential::SetStringValue(DWORD, const wchar_t*)      { return E_NOTIMPL; }
IFACEMETHODIMP WolfCredential::SetCheckboxValue(DWORD, BOOL)             { return E_NOTIMPL; }
IFACEMETHODIMP WolfCredential::SetComboBoxSelectedValue(DWORD, DWORD)    { return E_NOTIMPL; }
IFACEMETHODIMP WolfCredential::CommandLinkClicked(DWORD)                 { return E_NOTIMPL; }

IFACEMETHODIMP WolfCredential::GetUserSid(wchar_t** sid)
{
    std::lock_guard<std::mutex> lock(_sidGuard);

    // Reporting the enrolled account's SID is what makes this usable for
    // unlocking. Windows matches an unlock tile against the session it belongs
    // to, and a credential with no SID is an "other user" tile that LogonUI
    // will not auto-submit for a locked session.
    if (!_userSid.empty()) return CopyOut(_userSid, sid);

    // Nothing enrolled, or the agent is not answering. S_FALSE is the honest
    // answer and leaves the tile in the generic position rather than claiming
    // an account it cannot sign in.
    *sid = nullptr;
    return S_FALSE;
}

// ----------------------------------------------------------- serialization

IFACEMETHODIMP WolfCredential::GetSerialization(
    CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* response,
    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* serialization,
    wchar_t** statusText,
    CREDENTIAL_PROVIDER_STATUS_ICON* statusIcon)
{
    *statusText = nullptr;
    *statusIcon = CPSI_NONE;

    Note("GetSerialization called");

    // Second pass: the worker finished and LogonUI came back for the goods.
    if (_ready.load())
    {
        Note("packing the credential");
        const HRESULT hr = PackCredential(serialization);
        if (FAILED(hr))
        {
            _ready.store(false);
            Wipe();
            *response = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
            *statusIcon = CPSI_ERROR;
            CopyOut(L"Could not hand the credential to Windows.", statusText);
            return S_OK;
        }

        // Packed and copied. Nothing here needs the plaintext any more, and
        // this process outlives the login.
        Wipe();
        _ready.store(false);
        Note("credential handed to Windows");
        *response = CPGSR_RETURN_CREDENTIAL_FINISHED;
        return S_OK;
    }

    // First pass: ask the worker to begin and stay on screen. Returning
    // NOT_FINISHED is what keeps the tile alive without blocking the thread
    // that draws it.
    _selected.store(true);
    _submit.store(true);

    *response = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
    return S_OK;
}

HRESULT WolfCredential::PackCredential(CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* serialization)
{
    std::lock_guard<std::mutex> lock(_credentialGuard);
    if (_username.empty()) return E_UNEXPECTED;

    KERB_INTERACTIVE_UNLOCK_LOGON logon{};
    logon.Logon.MessageType = (_scenario == CPUS_UNLOCK_WORKSTATION)
        ? KerbWorkstationUnlockLogon
        : KerbInteractiveLogon;

    InitUnicodeString(logon.Logon.LogonDomainName, _domain);
    InitUnicodeString(logon.Logon.UserName, _username);
    InitUnicodeString(logon.Logon.Password, _password);

    // One blob: the struct, then the three strings. LSA reads the Buffer
    // pointers as byte offsets from the start of the blob rather than as
    // addresses, which is what lets it cross the process boundary at all.
    ULONG size = sizeof(logon)
        + logon.Logon.LogonDomainName.Length
        + logon.Logon.UserName.Length
        + logon.Logon.Password.Length;

    auto* blob = static_cast<BYTE*>(CoTaskMemAlloc(size));
    if (!blob) return E_OUTOFMEMORY;

    BYTE* cursor = blob + sizeof(logon);

    auto relocate = [&](UNICODE_STRING& field)
    {
        if (field.Length) memcpy(cursor, field.Buffer, field.Length);
        field.Buffer = reinterpret_cast<wchar_t*>(cursor - blob);
        cursor += field.Length;
    };

    // Copy the payloads first, then overwrite the header with the version
    // whose pointers have become offsets.
    KERB_INTERACTIVE_UNLOCK_LOGON packed = logon;
    relocate(packed.Logon.LogonDomainName);
    relocate(packed.Logon.UserName);
    relocate(packed.Logon.Password);
    memcpy(blob, &packed, sizeof(packed));

    ULONG package = 0;
    const HRESULT hr = NegotiatePackage(&package);
    if (FAILED(hr))
    {
        SecureZeroMemory(blob, size);
        CoTaskMemFree(blob);
        return hr;
    }

    serialization->ulAuthenticationPackage = package;
    serialization->clsidCredentialProvider = CLSID_WolfCredentialProvider;
    serialization->cbSerialization = size;
    serialization->rgbSerialization = blob;
    return S_OK;
}

IFACEMETHODIMP WolfCredential::ReportResult(NTSTATUS status, NTSTATUS,
                                            wchar_t** statusText,
                                            CREDENTIAL_PROVIDER_STATUS_ICON* statusIcon)
{
    *statusText = nullptr;
    *statusIcon = CPSI_NONE;

    if (status == STATUS_SUCCESS) return S_OK;

    // The one failure worth naming, because it has a specific fix and is the
    // likeliest thing to go wrong months from now: the enrolled password is
    // stale because Windows' was changed and the sealed copy did not follow.
    if (status == STATUS_LOGON_FAILURE)
    {
        *statusIcon = CPSI_ERROR;
        CopyOut(L"Windows rejected the stored password. Run WolfAgent.exe "
                L"enroll-login again, and use the password tile meanwhile.",
                statusText);
    }

    Wipe();
    _ready.store(false);
    return S_OK;
}

// ------------------------------------------------------------ the waiting

void WolfCredential::StartWorker()
{
    if (_worker.joinable()) return;
    _stop.store(false);
    _worker = std::thread(&WolfCredential::Worker, this);
}

void WolfCredential::StopWorker()
{
    _stop.store(true);
    if (_worker.joinable()) _worker.join();
}

bool WolfCredential::Handshake(PipeClient& pipe)
{
    if (!pipe.Connect(2000)) return false;

    std::string reply;
    if (!pipe.Ask("HELLO", reply) || reply.rfind("WOLF ", 0) != 0)
    {
        pipe.Close();
        return false;
    }

    // Ask which account this tile unlocks, and resolve its SID.
    //
    // This is not cosmetic. At a lock screen Windows expects an unlock tile to
    // be bound to the account being unlocked; a credential that reports no SID
    // is an "other user" tile, and LogonUI will not auto-submit one to unlock
    // a session that belongs to somebody specific. Without this the credential
    // arrives, GetSerialization is never called, and nothing happens at all.
    if (pipe.Ask("WHO", reply) && reply.rfind("WHO ", 0) == 0)
    {
        const auto parts = Split(reply);
        if (parts.size() == 3)
        {
            const auto domain = Base64Decode(parts[1]);
            const auto user = Base64Decode(parts[2]);
            ResolveUserSid(Utf8ToWide(domain.data(), domain.size()),
                           Utf8ToWide(user.data(), user.size()));
        }
    }
    return true;
}

void WolfCredential::ResolveUserSid(const std::wstring& domain, const std::wstring& user)
{
    const std::wstring account = domain.empty() ? user : domain + L"\\" + user;

    BYTE sid[SECURITY_MAX_SID_SIZE]{};
    DWORD sidSize = sizeof sid;
    wchar_t foundDomain[256]{};
    DWORD foundDomainSize = 256;
    SID_NAME_USE use{};

    if (!LookupAccountNameW(nullptr, account.c_str(), sid, &sidSize,
                            foundDomain, &foundDomainSize, &use))
    {
        Note("could not resolve a SID for the enrolled account");
        return;
    }

    wchar_t* text = nullptr;
    if (!ConvertSidToStringSidW(sid, &text))
    {
        Note("could not format the enrolled account SID");
        return;
    }

    {
        std::lock_guard<std::mutex> lock(_sidGuard);
        _userSid = text;
    }
    LocalFree(text);
    Note("bound to the enrolled account");
}

void WolfCredential::Note(const std::string& message)
{
    std::lock_guard<std::mutex> lock(_logGuard);
    if (_logQueue.size() < 32) _logQueue.push_back(message);
}

/**
 * Owns the pipe for as long as the tile exists, and serves both directions.
 *
 * Reconnecting rather than giving up matters here. The agent restarts - on an
 * upgrade, on a crash, on a service recovery - and a provider that connected
 * once at startup and never again would go quietly dead until the machine was
 * rebooted, which is exactly the machine nobody can reach.
 */
void WolfCredential::Worker()
{
    using namespace std::chrono;

    PipeClient pipe;
    bool announced = false;

    while (!_stop.load())
    {
        if (!pipe.IsConnected())
        {
            if (!Handshake(pipe))
            {
                if (!announced)
                {
                    SetStatus(L"Cannot reach the WolfPC service on this PC. "
                              L"Use your password.");
                    announced = true;
                }
                std::this_thread::sleep_for(seconds(3));
                continue;
            }
            announced = false;
            SetStatus(L"Ready. Press Use my phone, or tap Unlock in the app.");
        }

        // Anything the UI thread wanted to say goes out here, since it has no
        // pipe of its own and LogonUI gives it nowhere else to say it.
        {
            std::vector<std::string> queued;
            {
                std::lock_guard<std::mutex> lock(_logGuard);
                queued.swap(_logQueue);
            }
            std::string ignored;
            for (const auto& message : queued) pipe.Ask("LOG " + message, ignored);
        }

        if (_submit.exchange(false))
        {
            RunChallengeFlow(pipe);
            continue;
        }

        // The phone-initiated half. The app sent a LOGIN command, the agent
        // verified its signature, and the approval is already proven - so
        // there is nothing to ask the phone and nothing to wait for.
        std::string reply;
        if (!pipe.Ask("PENDING", reply))
        {
            pipe.Close();
            continue;
        }

        if (reply.rfind("UNLOCK ", 0) == 0)
        {
            const std::string token = reply.substr(strlen("UNLOCK "));
            if (ClaimCredential(pipe, token))
            {
                SetStatus(L"Approved from your phone. Signing you in.");
                _ready.store(true);
                std::string ignored;
                pipe.Ask(_provider ? "LOG claimed; telling LogonUI to re-enumerate"
                                   : "LOG claimed, but there is no provider to notify",
                         ignored);
                if (_provider) _provider->OnCredentialReady();
            }
            continue;
        }

        std::this_thread::sleep_for(seconds(1));
    }
}

/** The at-the-keyboard half: ask the phone, and wait for a person. */
void WolfCredential::RunChallengeFlow(PipeClient& pipe)
{
    using namespace std::chrono;

    std::string reply;

    if (!pipe.Ask("CHALLENGE", reply) || reply.rfind("CHALLENGE ", 0) != 0)
    {
        // The agent writes its refusals to be read by a person - the commonest
        // being that no credential is enrolled on this PC - so the reason is
        // passed through rather than flattened into "it failed".
        const std::string detail = reply.rfind("ERR ", 0) == 0 ? reply.substr(4) : reply;
        const std::wstring wide = Utf8ToWide(
            reinterpret_cast<const BYTE*>(detail.c_str()), detail.size());
        SetStatus(wide.empty() ? L"The PC would not start a login request." : wide.c_str());
        if (reply.empty()) pipe.Close();
        return;
    }

    const std::string challengeId = reply.substr(strlen("CHALLENGE "));
    SetStatus(L"Check your phone, and approve with your fingerprint.");

    const auto deadline = steady_clock::now() + seconds(75);

    while (!_stop.load() && steady_clock::now() < deadline)
    {
        // Walking away from the tile abandons the request. Leaving it open
        // would mean a phone buzzing about a login nobody is waiting for.
        if (!_selected.load())
        {
            pipe.Ask("CANCEL " + challengeId, reply);
            SetStatus(L"Ready. Press Use my phone, or tap Unlock in the app.");
            return;
        }

        if (!pipe.Ask("AWAIT " + challengeId, reply))
        {
            SetStatus(L"Lost contact with the WolfPC service.");
            pipe.Close();
            return;
        }

        if (reply.rfind("APPROVED ", 0) == 0)
        {
            if (ClaimCredential(pipe, reply.substr(strlen("APPROVED "))))
            {
                SetStatus(L"Approved. Signing you in.");
                _ready.store(true);
                if (_provider) _provider->OnCredentialReady();
            }
            else
            {
                SetStatus(L"Approved, but the credential could not be read.");
            }
            return;
        }

        if (reply == "DENIED")  { SetStatus(L"Refused on the phone.");           return; }
        if (reply == "EXPIRED") { SetStatus(L"The request expired. Try again."); return; }
        if (reply.rfind("ERR", 0) == 0)
        {
            SetStatus(L"The PC could not complete the login.");
            return;
        }

        std::this_thread::sleep_for(seconds(1));
    }

    pipe.Ask("CANCEL " + challengeId, reply);
    SetStatus(L"Timed out waiting for your phone.");
}

bool WolfCredential::ClaimCredential(PipeClient& pipe, const std::string& token)
{
    std::string reply;
    if (!pipe.Ask("CLAIM " + token, reply) || reply.rfind("CRED ", 0) != 0) return false;

    const auto parts = Split(reply);
    if (parts.size() != 4) return false;

    const auto domain = Base64Decode(parts[1]);
    const auto user = Base64Decode(parts[2]);
    const auto password = Base64Decode(parts[3]);
    if (user.empty()) return false;

    std::lock_guard<std::mutex> lock(_credentialGuard);
    _domain = Utf8ToWide(domain.data(), domain.size());
    _username = Utf8ToWide(user.data(), user.size());
    // Already UTF-16LE on the wire, so it is copied rather than converted - a
    // conversion would be a second plaintext copy with no owner to wipe it.
    _password.assign(reinterpret_cast<const wchar_t*>(password.data()),
                     password.size() / sizeof(wchar_t));
    return true;
}

void WolfCredential::SetStatus(const wchar_t* text)
{
    {
        std::lock_guard<std::mutex> lock(_statusGuard);
        _status = text;
    }

    // Best effort. If LogonUI has already torn the tile down there is nothing
    // to update, and failing to repaint a status line is never a reason to
    // fail a login.
    if (_events) _events->SetFieldString(this, WFIELD_STATUS, text);
}

void WolfCredential::Wipe()
{
    std::lock_guard<std::mutex> lock(_credentialGuard);
    SecureWipe(_password);
    _username.clear();
    _domain.clear();
}
