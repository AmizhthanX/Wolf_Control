#include "relay.h"
#include "config.h"

RelayBank relays;

static inline uint8_t pinFor(RelayChannel ch) {
  return ch == RelayChannel::Power ? RELAY_POWER_PIN : RELAY_RESET_PIN;
}

const char* pulseResultName(PulseResult r) {
  switch (r) {
    case PulseResult::Started:         return "started";
    case PulseResult::RefusedBusy:     return "busy";
    case PulseResult::RefusedCooldown: return "cooldown";
  }
  return "unknown";
}

void RelayBank::write(RelayChannel ch, bool on) {
#if RELAY_ACTIVE_LOW
  digitalWrite(pinFor(ch), on ? LOW : HIGH);
#else
  digitalWrite(pinFor(ch), on ? HIGH : LOW);
#endif
}

void RelayBank::begin() {
  // Order matters. Write the inactive level *before* switching the pin to an
  // output: doing it the other way round drives the default 0 for a moment,
  // which on an active-low board is a relay click, which on a wired-in board
  // is a power button press. The external pull-up covers the window before
  // this function runs at all.
#if RELAY_ACTIVE_LOW
  digitalWrite(RELAY_POWER_PIN, HIGH);
  digitalWrite(RELAY_RESET_PIN, HIGH);
#else
  digitalWrite(RELAY_POWER_PIN, LOW);
  digitalWrite(RELAY_RESET_PIN, LOW);
#endif
  pinMode(RELAY_POWER_PIN, OUTPUT);
  pinMode(RELAY_RESET_PIN, OUTPUT);
  write(RelayChannel::Power, false);
  write(RelayChannel::Reset, false);

  active_ = false;
  // Pretend a pulse just ended, so the cooldown also covers the first seconds
  // after a reset. If the ESP32 is rebooting in a loop, it must not be able to
  // hammer the power button once per boot.
  lastEnd_ = millis();
}

void RelayBank::allOff() {
  write(RelayChannel::Power, false);
  write(RelayChannel::Reset, false);
  if (active_) lastEnd_ = millis();
  active_ = false;
}

void RelayBank::tick() {
  if (!active_) return;
  if (millis() - startedAt_ >= duration_) {
    write(channel_, false);
    active_ = false;
    lastEnd_ = millis();
    Serial.printf("[relay] %s released after %lums\n",
                  channel_ == RelayChannel::Power ? "power" : "reset",
                  (unsigned long)duration_);
  }
}

PulseResult RelayBank::pulse(RelayChannel ch, uint32_t requestedMs) {
  if (active_) return PulseResult::RefusedBusy;

  const uint32_t since = millis() - lastEnd_;
  if (since < RELAY_COOLDOWN_MS) return PulseResult::RefusedCooldown;

  uint32_t ms = requestedMs;
  if (ms == 0) ms = 1;
  if (ms > RELAY_MAX_PULSE_MS) ms = RELAY_MAX_PULSE_MS;

  channel_   = ch;
  duration_  = ms;
  startedAt_ = millis();
  active_    = true;
  pulseCount_++;
  write(ch, true);

  Serial.printf("[relay] %s closed for %lums (pulse #%lu)\n",
                ch == RelayChannel::Power ? "power" : "reset",
                (unsigned long)ms, (unsigned long)pulseCount_);
  return PulseResult::Started;
}

const char* RelayBank::stateName() const {
  if (!active_) return "idle";
  return channel_ == RelayChannel::Power ? "power" : "reset";
}
