#pragma once

#include <windows.h>
#include <credentialprovider.h>
#include <string>
#include <thread>
#include <atomic>
#include <mutex>
#include <vector>

#include "PipeClient.h"

class WolfProvider;

enum WolfField
{
    WFIELD_LABEL = 0,     // the tile's large text
    WFIELD_SUBMIT,        // "Use my phone"
    WFIELD_STATUS,        // what is happening, in words
    WFIELD_COUNT
};

/**
 * One tile: "Sign in with your phone".
 *
 * ## Why this needs a background thread
 *
 * Approving a login is a human act - pick up a phone, look at it, touch a
 * sensor - and the challenge allows 60 seconds for it. GetSerialization cannot
 * wait that long. It runs on the thread that draws the login screen, and a
 * credential provider that blocks there freezes the whole screen: no tiles, no
 * password box, nothing to do but hold the power button.
 *
 * So the wait happens elsewhere, and the flow is:
 *
 *   1. The user submits. GetSerialization sets a flag and returns
 *      CPGSR_NO_CREDENTIAL_NOT_FINISHED, which keeps the tile on screen and
 *      alive. The status line says to check the phone.
 *   2. The worker asks the agent for a challenge and polls AWAIT, updating the
 *      status line as it goes.
 *   3. On approval it claims the credential, stores it, and tells the provider
 *      its credentials changed.
 *   4. LogonUI re-enumerates. The provider now reports auto-logon, so LogonUI
 *      calls GetSerialization again - and this time there is something to
 *      return.
 *
 * ## Why the worker runs the whole time, not just after a submit
 *
 * There are two ways to start a login and only one of them begins at this PC.
 * The other begins on the phone: the machine is locked, you tap Unlock in the
 * app, and a LOGIN command reaches the agent. Nobody is standing at the
 * keyboard to press a button in that story, so something has to be listening.
 *
 * So one thread owns the pipe for as long as the tile exists. It polls PENDING
 * for app-initiated logins and runs the challenge flow when the button is
 * pressed. Holding the connection open is also what tells the agent a provider
 * is installed at all - phone_login_available is literally "is something on the
 * other end of this pipe" - and the app uses that to decide whether offering
 * Unlock would do anything.
 *
 * ## The password
 *
 * It exists in this process, in _password, between the claim and the pack. It
 * has to: Windows authenticates the user itself and wants a
 * KERB_INTERACTIVE_UNLOCK_LOGON. What this design avoids is the password
 * existing anywhere it is not needed, and it is wiped the moment it has been
 * packed - see Wipe().
 */
class WolfCredential final : public ICredentialProviderCredential2
{
public:
    WolfCredential() = default;
    ~WolfCredential();

    HRESULT Initialize(CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario,
                       WolfProvider* provider,
                       const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR* descriptors);

    /** True once the worker has a credential ready to hand to Windows. */
    bool IsReady() const { return _ready.load(); }

    // IUnknown
    IFACEMETHODIMP_(ULONG) AddRef() override;
    IFACEMETHODIMP_(ULONG) Release() override;
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override;

    // ICredentialProviderCredential
    IFACEMETHODIMP Advise(ICredentialProviderCredentialEvents* events) override;
    IFACEMETHODIMP UnAdvise() override;
    IFACEMETHODIMP SetSelected(BOOL* autoLogon) override;
    IFACEMETHODIMP SetDeselected() override;
    IFACEMETHODIMP GetFieldState(DWORD field,
                                 CREDENTIAL_PROVIDER_FIELD_STATE* state,
                                 CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* interactive) override;
    IFACEMETHODIMP GetStringValue(DWORD field, wchar_t** value) override;
    IFACEMETHODIMP GetBitmapValue(DWORD field, HBITMAP* bitmap) override;
    IFACEMETHODIMP GetCheckboxValue(DWORD field, BOOL* checked, wchar_t** label) override;
    IFACEMETHODIMP GetSubmitButtonValue(DWORD field, DWORD* adjacentTo) override;
    IFACEMETHODIMP GetComboBoxValueCount(DWORD field, DWORD* items, DWORD* selected) override;
    IFACEMETHODIMP GetComboBoxValueAt(DWORD field, DWORD item, wchar_t** value) override;
    IFACEMETHODIMP SetStringValue(DWORD field, const wchar_t* value) override;
    IFACEMETHODIMP SetCheckboxValue(DWORD field, BOOL checked) override;
    IFACEMETHODIMP SetComboBoxSelectedValue(DWORD field, DWORD item) override;
    IFACEMETHODIMP CommandLinkClicked(DWORD field) override;
    IFACEMETHODIMP GetSerialization(CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* response,
                                    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* serialization,
                                    wchar_t** statusText,
                                    CREDENTIAL_PROVIDER_STATUS_ICON* statusIcon) override;
    IFACEMETHODIMP ReportResult(NTSTATUS status, NTSTATUS substatus,
                                wchar_t** statusText,
                                CREDENTIAL_PROVIDER_STATUS_ICON* statusIcon) override;

    // ICredentialProviderCredential2
    IFACEMETHODIMP GetUserSid(wchar_t** sid) override;

private:
    void StartWorker();
    void StopWorker();
    void Worker();
    bool Handshake(PipeClient& pipe);
    void ResolveUserSid(const std::wstring& domain, const std::wstring& user);
    void Note(const std::string& message);   // queued; the worker sends it
    void RunChallengeFlow(PipeClient& pipe);
    bool ClaimCredential(PipeClient& pipe, const std::string& token);
    void SetStatus(const wchar_t* text);
    void Wipe();

    HRESULT PackCredential(CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* serialization);

    long _refs = 1;

    CREDENTIAL_PROVIDER_USAGE_SCENARIO _scenario = CPUS_INVALID;
    WolfProvider* _provider = nullptr;           // weak: the provider owns us
    ICredentialProviderCredentialEvents* _events = nullptr;
    const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR* _descriptors = nullptr;

    std::wstring _status = L"Select to sign in with your phone.";
    std::mutex _statusGuard;

    std::thread _worker;
    std::atomic<bool> _stop{false};      // tear down; the tile is going away
    std::atomic<bool> _submit{false};    // the user pressed the button
    std::atomic<bool> _selected{false};  // this tile has focus
    std::atomic<bool> _ready{false};     // a credential is waiting to be packed

    // Guards the three fields below, which the worker writes and the UI thread
    // reads when it packs.
    std::mutex _logGuard;
    std::vector<std::string> _logQueue;

    std::mutex _sidGuard;
    std::wstring _userSid;

    std::mutex _credentialGuard;
    std::wstring _domain;
    std::wstring _username;
    std::wstring _password;
};
