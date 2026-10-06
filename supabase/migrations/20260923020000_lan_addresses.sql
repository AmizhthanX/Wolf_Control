-- Let the devices report where they are on the LAN, so the app stops guessing.
--
-- The app pinned whatever address the pairing QR captured. DHCP moves those:
-- one router reboot and the stored address points at nothing, the LAN fallback
-- silently stops working, and the only symptom is a greyed-out button at the
-- moment the internet is down and nobody can look it up.
--
-- Both devices already talk to the control plane every few seconds. Reporting
-- an address costs nothing and means the app learns the current one whenever it
-- has cloud access, caching it for the outage when it does not.
--
-- This is also what removes DNS from the offline path. A phone with strict
-- Private DNS resolves nothing at all when the internet is down — including
-- mDNS names like wolfpc.local — so an address the app can dial directly is the
-- difference between the LAN fallback working and not.

alter table public.pc_status
  add column if not exists controller_lan_address text,
  add column if not exists agent_lan_address      text,
  -- Windows re-identifies a network as Public after a cable is unplugged, and
  -- a Private-scoped firewall rule is inert there. The agent knows; the app
  -- could not, and showed an unexplained dead button instead.
  add column if not exists agent_network_private  boolean not null default true;

comment on column public.pc_status.controller_lan_address is
  'host:port the ESP32 answers on, as it last reported. Cached by the app for offline use.';
comment on column public.pc_status.agent_lan_address is
  'host:port the Windows agent answers on, as it last reported.';
comment on column public.pc_status.agent_network_private is
  'False when Windows has classified the agent network Public, which makes the LAN firewall rule inert.';
