#include "ClassFactory.h"
#include "Provider.h"

extern long g_dllReferences;

WolfClassFactory::~WolfClassFactory() = default;

IFACEMETHODIMP_(ULONG) WolfClassFactory::AddRef()
{
    return static_cast<ULONG>(InterlockedIncrement(&_refs));
}

IFACEMETHODIMP_(ULONG) WolfClassFactory::Release()
{
    const long remaining = InterlockedDecrement(&_refs);
    if (remaining == 0) delete this;
    return static_cast<ULONG>(remaining);
}

IFACEMETHODIMP WolfClassFactory::QueryInterface(REFIID riid, void** ppv)
{
    if (!ppv) return E_POINTER;

    if (riid == IID_IUnknown || riid == IID_IClassFactory)
    {
        *ppv = static_cast<IClassFactory*>(this);
        AddRef();
        return S_OK;
    }

    *ppv = nullptr;
    return E_NOINTERFACE;
}

IFACEMETHODIMP WolfClassFactory::CreateInstance(IUnknown* outer, REFIID riid, void** ppv)
{
    if (!ppv) return E_POINTER;
    *ppv = nullptr;

    if (outer) return CLASS_E_NOAGGREGATION;

    auto* provider = new (std::nothrow) WolfProvider();
    if (!provider) return E_OUTOFMEMORY;

    const HRESULT hr = provider->QueryInterface(riid, ppv);
    provider->Release();
    return hr;
}

IFACEMETHODIMP WolfClassFactory::LockServer(BOOL lock)
{
    if (lock) InterlockedIncrement(&g_dllReferences);
    else      InterlockedDecrement(&g_dllReferences);
    return S_OK;
}
