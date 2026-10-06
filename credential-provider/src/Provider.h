#pragma once

#include <windows.h>
#include <credentialprovider.h>

class WolfCredential;

/**
 * The provider: one tile, and the bridge back to LogonUI when the phone
 * answers.
 *
 * It deliberately does not implement ICredentialProviderFilter. Filtering is
 * how a provider hides the other tiles, and hiding the password tile here would
 * mean a stale enrolled password, a wedged service or a bug in this DLL leaves
 * no way into the machine. Microsoft's own guidance is to keep a system
 * provider available, and on a PC whose whole purpose is being reachable when
 * nobody is near it, the recovery path is worth more than the tidiness.
 */
class WolfProvider final : public ICredentialProvider
{
public:
    WolfProvider() = default;
    ~WolfProvider();

    /**
     * The worker found an approval. Tell LogonUI its credentials changed; it
     * re-enumerates, sees auto-logon, and calls GetSerialization - which is the
     * only way to submit a credential from a thread that is not the one
     * drawing the screen.
     */
    void OnCredentialReady();

    // IUnknown
    IFACEMETHODIMP_(ULONG) AddRef() override;
    IFACEMETHODIMP_(ULONG) Release() override;
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override;

    // ICredentialProvider
    IFACEMETHODIMP SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario,
                                    DWORD flags) override;
    IFACEMETHODIMP SetSerialization(
        const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* serialization) override;
    IFACEMETHODIMP Advise(ICredentialProviderEvents* events, UINT_PTR context) override;
    IFACEMETHODIMP UnAdvise() override;
    IFACEMETHODIMP GetFieldDescriptorCount(DWORD* count) override;
    IFACEMETHODIMP GetFieldDescriptorAt(DWORD index,
                                        CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** descriptor) override;
    IFACEMETHODIMP GetCredentialCount(DWORD* count, DWORD* defaultIndex,
                                      BOOL* autoLogonWithDefault) override;
    IFACEMETHODIMP GetCredentialAt(DWORD index,
                                   ICredentialProviderCredential** credential) override;

private:
    void ReleaseCredential();

    long _refs = 1;
    CREDENTIAL_PROVIDER_USAGE_SCENARIO _scenario = CPUS_INVALID;
    WolfCredential* _credential = nullptr;
    ICredentialProviderEvents* _events = nullptr;
    UINT_PTR _adviseContext = 0;
};
