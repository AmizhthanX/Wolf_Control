# Tools

## `provision/` — create the PC, its devices and their secrets

```bash
cd tools/provision
cp .env.example .env          # project url, service role key, WOLF_DEVICE_KEY
node provision.mjs genkey                                   # make WOLF_DEVICE_KEY
node provision.mjs init --email you@example.com --pc PC-001 # the one-time setup
node provision.mjs status                                   # who is reporting in
node provision.mjs paircode                                 # if the agent cannot reach the cloud
node provision.mjs rotate --device wolfpc-001               # after a suspected leak
```

`init` prints the two device secrets **once**. They are stored AES-256-GCM
encrypted under `WOLF_DEVICE_KEY` and cannot be read back — losing them means
rotating.

No dependencies; it speaks PostgREST and the auth admin API over `fetch`.

## `simulator/` — a fake controller and a fake agent

Runs the real protocol against the real control plane, so the app and the Edge
Functions can be exercised before any hardware exists, and so a failing command
can be diagnosed without opening the case.

```bash
cd tools/simulator
cp .env.example .env
node simulate.mjs both --controller-secret <hex> --agent-secret <hex>
```

The controller half also serves the LAN routes on `http://localhost:8080`, so
the app's offline path can be tested by pointing Settings at that address.

It performs the same signature verification the firmware does, so an envelope
the simulator rejects is one the real hardware would reject too. That is the
point of it: a bug in signing shows up here, in a stack trace, rather than as a
relay that silently does not click.
