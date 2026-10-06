#pragma once

// Copy to credentials.h and fill in. credentials.h is gitignored.
//
// These values are written into NVS on first boot and then read from NVS, so
// you can also change them later over the serial console without reflashing
// (type `help`). Whatever is compiled in wins on a fresh chip.

// ------------------------------------------------------------------ wifi ----
#define CFG_WIFI_SSID     "your-ssid"
#define CFG_WIFI_PASSWORD "your-wifi-password"

// ---------------------------------------------------------------- device ----
// From: node tools/provision/provision.mjs init --email you@example.com
#define CFG_DEVICE_ID     "wolfpc-001"
#define CFG_DEVICE_SECRET "64 lowercase hex characters from the provisioning output"

// ----------------------------------------------------------------- cloud ----
// Your project's base URL, no trailing slash, e.g. https://abcdefgh.supabase.co
#define CFG_SUPABASE_URL  "https://your-project.supabase.co"

// The anon key. It is a public value — it identifies the project, it does not
// authorise anything. The device's real credential is CFG_DEVICE_SECRET.
#define CFG_SUPABASE_ANON_KEY "eyJhbGciOi..."
