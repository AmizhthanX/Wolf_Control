#pragma once

#include <initguid.h>
#include <guiddef.h>

// {DC970930-8A57-4F93-8494-F5F2328AAF87}
//
// This value is the provider's identity to Windows. It appears in the registry
// key under Authentication\Credential Providers and in nothing else, so it must
// never change once a machine has been registered - a new GUID leaves the old
// key behind pointing at a DLL that may no longer exist, and an orphaned
// registration is a tile that fails at the login screen.
DEFINE_GUID(CLSID_WolfCredentialProvider,
    0xdc970930, 0x8a57, 0x4f93, 0x84, 0x94, 0xf5, 0xf2, 0x32, 0x8a, 0xaf, 0x87);
