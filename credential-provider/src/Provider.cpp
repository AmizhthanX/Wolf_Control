#include "Provider.h"
#include "Credential.h"

namespace
{
    // Three fields: what the tile is called, the button, and a line of prose
    // that carries the whole story - waiting, approved, refused, expired, or
    // the reason the PC would not start.
    CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR kFields[WFIELD_COUNT] =
    {
        { WFIELD_LABEL,  CPFT_LARGE_TEXT,    const_cast<wchar_t*>(L"WolfPC") },
        { WFIELD_SUBMIT, CPFT_SUBMIT_BUTTON, const_cast<wchar_t*>(L"Use my phone") },
        { WFIELD_STATUS, CPFT_SMALL_TEXT,    const_cast<wchar_t*>(L"Status") },
    };
}

WolfProvider::~WolfProvider()
{
    ReleaseCredential();
    if (_events) { _events->Release(); _events = nullptr; }
}

void WolfProvider::ReleaseCredential()
{
    if (_credential)
    {
        _credential->Release();
        _credential = nullptr;
    }
}

void WolfProvider::OnCredentialReady()
{
    if (_events) _events->CredentialsChanged(_adviseContext);
}

// ------------------------------------------------------------------ IUnknown

IFACEMETHODIMP_(ULONG) WolfProvider::AddRef()
{
    return static_cast<ULONG>(InterlockedIncrement(&_refs));
}

IFACEMETHODIMP_(ULONG) WolfProvider::Release()
{
    const long remaining = InterlockedDecrement(&_refs);
    if (remaining == 0) delete this;
    return static_cast<ULONG>(remaining);
}

IFACEMETHODIMP WolfProvider::QueryInterface(REFIID riid, void** ppv)
{
    if (!ppv) return E_POINTER;

    if (riid == IID_IUnknown || riid == IID_ICredentialProvider)
    {
        *ppv = static_cast<ICredentialProvider*>(this);
        AddRef();
        return S_OK;
    }

    *ppv = nullptr;
    return E_NOINTERFACE;
}

// ------------------------------------------------------------------ scenario

IFACEMETHODIMP WolfProvider::SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario,
                                              DWORD)
{
    switch (scenario)
    {
    case CPUS_LOGON:
    case CPUS_UNLOCK_WORKSTATION:
        _scenario = scenario;
        ReleaseCredential();
        _credential = new (std::nothrow) WolfCredential();
        if (!_credential) return E_OUTOFMEMORY;
        return _credential->Initialize(scenario, this, kFields);

    // Everything else is declined rather than half-supported. Credential UI is
    // an app asking for a password inside a running session, and change-password
    // wants a password this provider does not have and must never be asked for.
    // A provider that answers those badly is a provider that breaks unrelated
    // parts of Windows.
    case CPUS_CREDUI:
    case CPUS_CHANGE_PASSWORD:
    default:
        return E_NOTIMPL;
    }
}

IFACEMETHODIMP WolfProvider::SetSerialization(
    const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION*)
{
    return E_NOTIMPL;
}

IFACEMETHODIMP WolfProvider::Advise(ICredentialProviderEvents* events, UINT_PTR context)
{
    if (_events) _events->Release();
    _events = events;
    _adviseContext = context;
    if (_events) _events->AddRef();
    return S_OK;
}

IFACEMETHODIMP WolfProvider::UnAdvise()
{
    if (_events) { _events->Release(); _events = nullptr; }
    _adviseContext = 0;
    return S_OK;
}

// -------------------------------------------------------------------- fields

IFACEMETHODIMP WolfProvider::GetFieldDescriptorCount(DWORD* count)
{
    if (!count) return E_POINTER;
    *count = WFIELD_COUNT;
    return S_OK;
}

IFACEMETHODIMP WolfProvider::GetFieldDescriptorAt(
    DWORD index, CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** descriptor)
{
    if (index >= WFIELD_COUNT || !descriptor) return E_INVALIDARG;

    // LogonUI frees these, so each one is a fresh allocation rather than a
    // pointer into the static table.
    auto* copy = static_cast<CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR*>(
        CoTaskMemAlloc(sizeof(CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR)));
    if (!copy) return E_OUTOFMEMORY;

    *copy = kFields[index];

    const size_t bytes = (wcslen(kFields[index].pszLabel) + 1) * sizeof(wchar_t);
    auto* label = static_cast<wchar_t*>(CoTaskMemAlloc(bytes));
    if (!label)
    {
        CoTaskMemFree(copy);
        return E_OUTOFMEMORY;
    }
    memcpy(label, kFields[index].pszLabel, bytes);
    copy->pszLabel = label;

    *descriptor = copy;
    return S_OK;
}

// --------------------------------------------------------------- credentials

IFACEMETHODIMP WolfProvider::GetCredentialCount(DWORD* count, DWORD* defaultIndex,
                                                BOOL* autoLogonWithDefault)
{
    if (!count || !defaultIndex || !autoLogonWithDefault) return E_POINTER;

    *count = _credential ? 1 : 0;
    *defaultIndex = 0;

    // The whole point of the round trip. When the worker has a credential
    // waiting, saying so here makes LogonUI call GetSerialization by itself -
    // which is how an approval that arrived on a background thread turns into
    // a login without anyone touching the machine.
    *autoLogonWithDefault = (_credential && _credential->IsReady()) ? TRUE : FALSE;

    // Never the default tile otherwise. Becoming default would put this in
    // front of the password tile on every boot, including the boots where the
    // service is not up yet.
    if (!*autoLogonWithDefault) *defaultIndex = CREDENTIAL_PROVIDER_NO_DEFAULT;

    return S_OK;
}

IFACEMETHODIMP WolfProvider::GetCredentialAt(DWORD index,
                                             ICredentialProviderCredential** credential)
{
    if (index != 0 || !credential || !_credential) return E_INVALIDARG;
    return _credential->QueryInterface(IID_PPV_ARGS(credential));
}
