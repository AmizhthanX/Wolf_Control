#pragma once
#include <Arduino.h>

// The LAN API (PRD §8): what keeps POWER ON and HARD RESET working when the
// internet is down but the phone is on the home Wi-Fi.
//
// Authentication is the same signed envelope the cloud path uses, verified
// against the phone keys pinned at pairing time. There is no "local network is
// trusted" mode — PRD §18 is explicit that finding the ESP32 on the LAN must
// not be enough to power-cycle the PC.

namespace localapi {

void begin();
void tick();

}  // namespace localapi
