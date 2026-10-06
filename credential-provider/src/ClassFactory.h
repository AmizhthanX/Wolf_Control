#pragma once

#include <windows.h>
#include <unknwn.h>
#include <new>

/** Standard COM plumbing: hands out WolfProvider instances to LogonUI. */
class WolfClassFactory final : public IClassFactory
{
public:
    WolfClassFactory() = default;
    ~WolfClassFactory();

    IFACEMETHODIMP_(ULONG) AddRef() override;
    IFACEMETHODIMP_(ULONG) Release() override;
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override;

    IFACEMETHODIMP CreateInstance(IUnknown* outer, REFIID riid, void** ppv) override;
    IFACEMETHODIMP LockServer(BOOL lock) override;

private:
    long _refs = 1;
};
