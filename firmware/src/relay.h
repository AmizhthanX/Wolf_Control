#pragma once
#include <Arduino.h>

// The only code in this project that is allowed to move a relay.
//
// Design rules, all from PRD §7 and §25:
//   - both channels are inactive before anything else happens at boot
//   - a pulse is non-blocking, so the watchdog keeps getting fed and the local
//     API keeps answering while a relay is closed
//   - every pulse is released by tick(), and the release is clamped to
//     RELAY_MAX_PULSE_MS no matter what the caller asked for
//   - power and reset are mutually exclusive, and a cooldown separates pulses

enum class RelayChannel : uint8_t { Power = 0, Reset = 1 };

enum class PulseResult : uint8_t {
  Started,
  RefusedBusy,      // the other channel, or this one, is mid-pulse
  RefusedCooldown,  // too soon after the last pulse
};

const char* pulseResultName(PulseResult r);

class RelayBank {
 public:
  void begin();

  // Safe to call as often as you like; does nothing unless a pulse is due to
  // end. Must be called from loop() — it is what guarantees release.
  void tick();

  PulseResult pulse(RelayChannel ch, uint32_t requestedMs);

  bool busy() const { return active_; }
  RelayChannel activeChannel() const { return channel_; }
  uint32_t lastPulseEndedAt() const { return lastEnd_; }

  // "idle" | "power" | "reset", for /api/status and the poll heartbeat.
  const char* stateName() const;

  // Emergency stop: drop both channels now. Used by the panic path.
  void allOff();

 private:
  void write(RelayChannel ch, bool on);

  bool     active_  = false;
  RelayChannel channel_ = RelayChannel::Power;
  uint32_t startedAt_ = 0;
  uint32_t duration_  = 0;
  uint32_t lastEnd_   = 0;
  uint32_t pulseCount_ = 0;
};

extern RelayBank relays;
