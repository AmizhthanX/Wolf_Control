# Phase 2 — wiring the relays to the motherboard

A standalone procedure, because the machine being worked on is the machine the
instructions live on. Read it through once before starting.

Target: Gigabyte B450 AORUS M. The `F_PANEL` header carries `PW` and `RES` as
separate pin pairs.

## What this achieves

The relay contacts end up **in parallel** with the case's own buttons, so both
work independently. That is the hard requirement in PRD §26: ESP32 dead, Wi-Fi
dead, internet dead, and the case buttons still work.

## Before shutting down

1. **Move the ESP32 to a phone charger.** It is currently powered by the PC's
   USB and would die with it — and then it cannot turn the PC back on. Confirm
   the app still shows the controller online afterwards.
2. Run `test power` and `test reset` over serial. **Note which of the two blue
   cubes clicks for each.** The power one is relay 1.
3. Read the labels on the screw terminals: `NO`, `COM`, `NC`.
4. Gather: 12 jumper wires (a mix of male-to-female and male-to-male), the
   breadboard, a small screwdriver, and a phone with these instructions.

## Shutting down safely

1. Shut Windows down normally, fully.
2. Switch the PSU off at the back of the case — the rocker beside the power
   cable, pressed to **O**.
3. Hold the case power button for **5 seconds**. Nothing happens; that is the
   point. It drains the residual charge.
4. Open the side panel.
5. **Photograph the `F_PANEL` header with every plug still on it.** If a
   connector comes off by accident, that photo is how it goes back.

## The junction

Each of the two switches needs three conductors meeting at each of its two
terminals: the motherboard pin, the case button, and the relay. A breadboard row
is the simplest reversible way to join three things without soldering.

Keep the breadboard **outside the case** during testing, with the wires routed
out through the open side. Nothing loose should rest on the motherboard.

### Power switch

1. Unplug the plug labelled `POWER SW`. The two pins it was on are `PW`.
2. Two **male-to-female** jumpers: push a female end onto each `PW` pin, and
   the male ends into breadboard **rows 1 and 2**.
3. Two **male-to-male** jumpers: row 1 to one hole of the `POWER SW` plug, row 2
   to its other hole. The case button is now live again.
4. Two more jumpers: row 1 to relay 1 `COM`, row 2 to relay 1 `NO`. Tighten the
   screws onto the metal, not the insulation.

### Reset switch

The same again with the plug labelled `RESET SW`, breadboard rows 3 and 4, and
relay 2's `COM` and `NO`.

Polarity does not matter for either switch. A momentary switch is just a short —
only the LEDs care about direction, and we are not touching those.

## Testing, in this order

1. PSU rocker back to **I**.
2. **Press the case power button.** The PC should start. This proves the
   physical path survived — test it before anything else.
3. Shut Windows down again and wait until it is fully off.
4. **Tap POWER ON in the app.** The PC should start.
5. Leave it at the login screen. Within 10–30 seconds the app should show
   *Windows: Login screen* on its own.
6. **Press the case reset button.** The PC should reset.
7. **HARD RESET from the app** — type `RESET` to unlock the button. The PC
   should reset.

All four paths — two physical, two remote — must work. If any one fails, stop
and fix it before closing the case.

## If something goes wrong

**The case power button does nothing.** The `POWER SW` plug is not seated, or
the jumpers into its holes are loose. Compare against your photo.

**The relay clicks but the PC does not react.** Wrong screw terminals — it must
be `COM` and `NO`, not `NC`. Or the wires are on the wrong `F_PANEL` pins.

**Nothing works and you need the PC on now.** Briefly touch a screwdriver blade
across the two `PW` pins. That is all the power button does. One second is
enough.

**The PC starts and immediately powers off.** A relay is stuck closed, which
reads as a long power-button hold. Unplug the ESP32's USB; the contact opens
and the machine behaves normally again.

## Afterwards

Leave the case open until the whole system has been exercised for a few days.
Phase 3 — moving the ESP32 onto the PSU's 5 V standby rail so it survives with
the PC off — needs to get in there again anyway.
