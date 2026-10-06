# Hardware build — phases 1 to 3

Read this once end to end before touching the PC. Phases 1 and 2 are cheap to
get wrong on a bench and expensive to get wrong inside a case.

Target: Gigabyte B450 AORUS M, Corsair CV450, ESP32 dev board, 5 V 2-channel
relay module.

---

## The one thing that can bite you

Between power-on and the first line of `setup()`, an ESP32 GPIO is a **floating
input**. On a relay board that is **active LOW** — which most cheap blue
2-channel boards are — floating reads as "energise", so the relay clicks on
every ESP32 reset.

Wired to `PWR_SW`, that is a power button press every time the controller
reboots. Wired to `RES`, it is a hard reset of a running machine.

The firmware parks both channels in the first statement of `setup()`, but that
runs milliseconds too late on its own. **The fix is a resistor, not code:**

```
        3V3
         |
        10k          <- one per channel
         |
GPIO 18 -+----------- relay IN1
         |
        ESP32
```

Fit a 10 kΩ pull-up from each relay input to 3V3. You have 10 kΩ resistors in
the parts list already (PRD §4). With the pull-up fitted, the input idles high —
de-energised on an active-low board — from the instant power arrives, through
the whole boot, until the firmware takes over.

### Unless your board is opto-isolated — test before you fit them

The advice above is for a board whose inputs drive a transistor directly. On an
**opto-isolated** board the input drives an LED inside an optocoupler, and that
LED needs a path to ground to light. A floating pin gives it none, so floating
is already safe and the resistors are solving a problem you do not have.

Worse, on such a board a pull-up to **3V3** is mildly harmful. The LED's anode
sits at 5 V, so pulling the cathode to 3.3 V leaves about 1.7 V across it —
close enough to its forward voltage to trickle current through the
optocoupler continuously. Floating leaves exactly zero. If you do want a
pull-up on an opto board, pull up to **5 V**, not 3V3.

So: run the boot test first, with no resistors, and only fit them if it clicks.

*(Measured on the reference build — a 2-channel opto-isolated board on an
ESP32-WROOM-32 DevKit, 30 pin: silent on all ten resets and silent through a
full flash cycle, with no resistors fitted. The resistors were left out.)*

### Check which kind of board you have

Before wiring anything to the motherboard:

1. Power the relay board, connect `IN1` to nothing.
2. Touch `IN1` to GND. If the relay clicks and the LED lights, it is
   **active LOW** — keep `RELAY_ACTIVE_LOW 1` in `firmware/include/config.h`.
3. Touch `IN1` to 3V3 instead. If *that* is what energises it, it is
   **active HIGH** — set `RELAY_ACTIVE_LOW 0`, and fit the 10 kΩ resistors as
   pull-*downs* to GND rather than pull-ups to 3V3.

Getting this backwards means the relay is energised whenever the ESP32 is *not*
driving it, which is the failure mode this whole section exists to prevent.

---

## Phase 1 — bench, no PC involved

Nothing connects to the computer in this phase.

**Wiring**

```
ESP32 5V   ──── relay VCC          (see the note on coil power below)
ESP32 GND  ──── relay GND
ESP32 G18  ──── relay IN1          + 10k to 3V3
ESP32 G19  ──── relay IN2          + 10k to 3V3
```

**If the input LED lights but the relay never clicks**, the coils have no
supply. The signal side and the coil side are powered separately, and many
boards ship with the `JD-VCC` / `VCC` / `GND` header **bare** — the little
jumper cap that normally bridges `JD-VCC` to `VCC` is simply not fitted. Bridge
those two pins with a female-to-female jumper and leave that header's `GND`
alone. Be certain which pin is which before you bridge: `VCC` to `GND` shorts
the supply.

This is worth ruling out early, because until the coils have power the boot
test below is meaningless — a dead coil cannot click no matter what the GPIO is
doing.

**Coil power.** Two energised coils draw roughly 140 mA. That is within what a
USB-powered dev board can pass, but it is close enough to the limit that a
brown-out during a pulse is a real possibility on a cheap board. If your relay
module has a `JD-VCC` jumper, remove it and feed `JD-VCC` from a separate 5 V
source, sharing only GND with the ESP32. That keeps the optocouplers doing their
job — the coil's switching noise never reaches the ESP32's rail.

**Tests**, over the serial console at 115200 baud:

| Test                       | Command       | Expect                                      |
| -------------------------- | ------------- | ------------------------------------------- |
| Both relays idle at boot   | reset the ESP32 | no click at all, on either channel        |
| Power pulse                | `test power`  | one click, ~1 s, one click back             |
| Reset pulse                | `test reset`  | one click, ~350 ms, one click back          |
| Cooldown                   | `test power` twice quickly | second answers `cooldown`      |
| Watchdog releases a relay  | pull USB mid-pulse, repower | relay open on boot            |

Reset the ESP32 ten times in a row and watch the relay LEDs. **Any click during
boot means the pull-up is missing or on the wrong rail.** Do not continue until
ten resets in a row are silent.

---

## Phase 2 — connect to the motherboard

Power off. Switch off the PSU at the back. Hold the case power button for five
seconds to drain.

The B450 AORUS M front panel header (`F_PANEL`) carries `PW` and `RES` as
separate pin pairs. The relay goes in **parallel** with the existing case
button, not in series and not instead of it:

```
                 ┌── case Power button ──┐
    F_PANEL PW ──┤                       ├── F_PANEL PW (ground side)
                 └── relay CH1 COM / NO ─┘

                 ┌── case Reset button ──┐
   F_PANEL RES ──┤                       ├── F_PANEL RES (ground side)
                 └── relay CH2 COM / NO ─┘
```

Use `COM` and `NO` — normally open. A de-energised relay is an open circuit and
the motherboard cannot tell the difference between that and no relay at all.
That is what satisfies PRD §26: **ESP32 dead, Wi-Fi dead, internet dead, and the
case buttons still work.**

**Never** connect ESP32 5 V, GND or a GPIO directly to `PW` or `RES` (PRD §25).
Those pins carry the motherboard's own pull-up voltage, and the only thing that
should ever bridge them is an isolated dry contact.

Practically, the tidiest join is a short dupont pigtail spliced into each
front-panel lead, heat-shrunk, with the relay leads long enough to reach wherever
the board is mounted and short enough not to loop near a fan.

**Tests, in this order:**

1. Case power button still turns the PC on. *(If this fails, stop and unwire.)*
2. Case reset button still resets it.
3. `test power` from serial turns the PC on.
4. `test reset` from serial resets it.
5. `test power` while the PC is running does nothing visible — a 1 s press is a
   short press, and Windows treats it as a sleep/shutdown request, not a kill.
6. Power and reset never fire together. The firmware enforces this in
   `RelayBank::pulse()`, which refuses a second channel while one is active.

---

## Phase 3 — standby power, so the controller survives S5

The whole remote-power feature depends on the ESP32 being alive while the PC is
off. PC USB ports are dead in S5 on most configurations, so the controller needs
the standby rail.

The CV450 supplies **+5 VSB at up to 3 A**; an ESP32 with two relays peaks well
under 0.3 A, so there is enormous headroom.

**Do not open the PSU.** Take standby power from the low-voltage output side,
through an ATX 24-pin breakout or passthrough:

```
ATX 24-pin, pin 9 (purple) = +5VSB
ATX 24-pin, any black      = GND
        |
   inline fuse, 500 mA
        |
   USB 5V / GND  ->  micro-USB  ->  ESP32
```

The fuse is not optional. It is the difference between a shorted jumper being an
annoyance and being a fire inside your case.

**Also worth trying first, because it needs no splicing at all:** on many B450
boards with **ErP disabled** in BIOS, the internal USB 2.0 headers stay powered
in S5. A plain internal-USB-to-micro-USB lead then does the whole job with no
breakout and no fuse. Test it: shut the PC down fully and see whether the ESP32
stays connected. If it does, you are finished; if it does not, use the 5VSB tap
above.

**BIOS.** Keep **ErP disabled** during development (PRD §6). ErP cuts standby
power to nearly everything in S5 to meet the EU standby-power rules, and it will
take the ESP32 down with it.

**Test:**

```
shut Windows down fully
   -> ESP32 stays powered
   -> it stays associated to Wi-Fi
   -> GET http://wolfpc.local/api/status still answers
   -> POWER ON from the app boots the machine
```

Leave it shut down overnight and check again in the morning. A controller that
survives five minutes but not eight hours usually means the board is browning
out on a marginal supply.

### As built on the reference machine

Tapped at the 24-pin connector: **pin 9 (purple, +5VSB)** and **pin 3 (black,
COM)**, into the cut end of a micro-USB cable — red to +5VSB, black to ground.
ESP32 and relay board mounted in a homemade enclosure inside the case.

Two things that changed once it went in the box, both worth checking on any
build:

- **Signal strength dropped from about -50 dBm to -68 dBm.** That is the steel
  case, and -68 is still comfortable. Watch it if you reposition anything:
  below roughly -80 the connection starts dropping and the controller will
  spend its time reconnecting instead of polling.
- **Every address on the network moved.** The PC went .38, then .36, then .40
  across one evening of reboots, and the ESP32 ended up on the address the PC
  had held an hour earlier. Anything that pins a LAN address by hand will break
  within days; the devices report their own and the app adopts them.

**This build has no inline fuse.** The 500 mA fuse described above is the one
part of this procedure that protects against something worse than a dead
component, and it remains the right advice. Fitting one later means unplugging
the micro-USB lead and splicing it into the positive line — ten minutes,
no disassembly of anything else.

---

## Mounting

Get it off the breadboard (PRD §4). Breadboard contacts work loose with
vibration and thermal cycling, and a contact that goes intermittent across
`PWR_SW` is a PC that reboots at random.

- solder to perfboard, or use a small project PCB
- mount in an enclosure or at least on a standoff; nothing bare against the case
- short insulated wires, heat-shrink every joint, no bare strands near a fan
- label the two relay leads `PWR` and `RES` — you will not remember in a year

---

## Quick reference

| Item                    | Value                                    |
| ----------------------- | ---------------------------------------- |
| Power relay GPIO        | 18                                       |
| Reset relay GPIO        | 19                                       |
| Power pulse             | 1000 ms                                  |
| Reset pulse             | 350 ms                                   |
| Hard ceiling on a pulse | 2000 ms, enforced in firmware            |
| Cooldown between pulses | 4000 ms                                  |
| Watchdog                | 30 s                                     |
| Pull-up per relay input | 10 kΩ to 3V3 (active-low boards)         |
| Standby rail            | +5VSB, ATX pin 9 (purple), 500 mA fuse   |
| BIOS                    | ErP **disabled**                         |
