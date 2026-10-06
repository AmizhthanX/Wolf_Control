#pragma once
#include <Arduino.h>

// Wi-Fi, SNTP and mDNS.
//
// The controller is expected to be up for months behind a PC's front panel,
// often with a router that reboots at 4am. Reconnection is therefore a normal
// event with exponential backoff, not an error path. (PRD §7, §29)

namespace netlink {

void begin();
void tick();

bool   connected();
bool   clockSynced();
String ip();
int    rssi();
uint32_t uptimeSeconds();

/** Seconds since the last successful association, for /api/status. */
uint32_t connectedForSeconds();

}  // namespace netlink
