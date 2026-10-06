#include <windows.h>
#include <unknwn.h>
#include <new>

#include "guid.h"
#include "ClassFactory.h"

long g_dllReferences = 0;
HINSTANCE g_instance = nullptr;

BOOL APIENTRY DllMain(HINSTANCE instance, DWORD reason, LPVOID)
{
    if (reason == DLL_PROCESS_ATTACH)
    {
        g_instance = instance;

        // LogonUI creates and destroys threads around this DLL constantly and
        // none of the notifications are useful here. Declining them is a small
        // saving on a process that draws the login screen.
        DisableThreadLibraryCalls(instance);
    }
    return TRUE;
}

STDAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, void** ppv)
{
    if (!ppv) return E_POINTER;
    *ppv = nullptr;

    if (rclsid != CLSID_WolfCredentialProvider) return CLASS_E_CLASSNOTAVAILABLE;

    auto* factory = new (std::nothrow) WolfClassFactory();
    if (!factory) return E_OUTOFMEMORY;

    const HRESULT hr = factory->QueryInterface(riid, ppv);
    factory->Release();
    return hr;
}

STDAPI DllCanUnloadNow()
{
    return g_dllReferences > 0 ? S_FALSE : S_OK;
}
