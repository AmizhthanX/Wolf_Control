#pragma once
#include <Arduino.h>

// Outbound-only link to Supabase. PRD §9: no public IP, no port forwarding,
// nothing of the ESP32 exposed to the internet. The controller polls; the
// cloud never connects in.

namespace cloud {

void begin();
void tick();

bool     online();            // last poll succeeded
uint32_t lastPollAt();
uint32_t consecutiveFailures();
uint32_t pollIntervalMs();

}  // namespace cloud
