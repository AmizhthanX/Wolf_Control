-- WolfPC Remote — core schema (PRD §20)
--
-- Identity model
--   pcs            one physical computer, e.g. 'PC-001'
--   devices        things that talk to the control plane and belong to a pc:
--                  the ESP32 controller, the Windows agent, and each paired phone
--   commands       always addressed to a pc id, then routed to CONTROLLER or AGENT
--
-- The envelope's device_id (PRD §13) is the *pc* id, which is why a single
-- POWER_ON reaches the controller while a SHUTDOWN reaches the agent.

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------- enums ----

create type wolf_device_type    as enum ('CONTROLLER', 'AGENT', 'PHONE');
create type wolf_device_status  as enum ('UNPAIRED', 'PAIRED', 'REVOKED');
create type wolf_key_type       as enum ('ECDSA_P256', 'HMAC_SHA256');
create type wolf_command        as enum ('POWER_ON', 'SHUTDOWN', 'RESTART', 'HARD_RESET', 'LOGIN');
create type wolf_command_status as enum ('QUEUED', 'SENT', 'ACKNOWLEDGED', 'EXECUTED', 'FAILED', 'EXPIRED');
create type wolf_windows_state  as enum ('OFF', 'BOOTING', 'LOGIN_SCREEN', 'LOGGED_IN',
                                         'SHUTTING_DOWN', 'RESTARTING', 'UNKNOWN');
create type wolf_session_state  as enum ('NONE', 'LOCKED', 'ACTIVE');
create type wolf_challenge_result as enum ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED');

-- ---------------------------------------------------------------- users ----

create table public.users (
  id         uuid primary key references auth.users (id) on delete cascade,
  email      text        not null,
  created_at timestamptz not null default now(),
  last_login timestamptz
);

comment on table public.users is 'Profile mirror of auth.users. Never holds credentials.';

-- Keep public.users in step with auth.users without the app having to remember.
create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.users (id, email)
  values (new.id, coalesce(new.email, ''))
  on conflict (id) do update set email = excluded.email;
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_auth_user();

-- ------------------------------------------------------------------ pcs ----

create table public.pcs (
  id         text primary key,
  owner_id   uuid        not null references public.users (id) on delete cascade,
  name       text        not null,
  created_at timestamptz not null default now()
);

create index pcs_owner_idx on public.pcs (owner_id);

-- -------------------------------------------------------------- devices ----

create table public.devices (
  id               text primary key,
  pc_id            text               references public.pcs (id) on delete cascade,
  owner_id         uuid      not null references public.users (id) on delete cascade,
  name             text      not null,
  device_type      wolf_device_type   not null,
  status           wolf_device_status not null default 'UNPAIRED',
  last_seen        timestamptz,
  firmware_version text,
  metadata         jsonb     not null default '{}'::jsonb,
  paired_at        timestamptz,
  created_at       timestamptz not null default now()
);

create index devices_pc_idx    on public.devices (pc_id, device_type);
create index devices_owner_idx on public.devices (owner_id);

-- A pc has at most one controller and one agent; phones are unlimited.
create unique index devices_one_controller_per_pc
  on public.devices (pc_id) where device_type = 'CONTROLLER';
create unique index devices_one_agent_per_pc
  on public.devices (pc_id) where device_type = 'AGENT';

-- ----------------------------------------------------------- device_keys ----
-- Public keys only. The matching private keys live in the Android Keystore and
-- in the agent's DPAPI store and never reach this database. (PRD §10)

create table public.device_keys (
  id         uuid primary key default gen_random_uuid(),
  device_id  text not null references public.devices (id) on delete cascade,
  public_key text not null,
  key_type   wolf_key_type not null default 'ECDSA_P256',
  created_at timestamptz not null default now(),
  revoked_at timestamptz
);

create index device_keys_active_idx
  on public.device_keys (device_id) where revoked_at is null;

-- -------------------------------------------------------- device_secrets ----
-- The HMAC secret shared with the ESP32 and the agent, AES-256-GCM encrypted
-- under WOLF_DEVICE_KEY, which lives only in Edge Function secrets. A dump of
-- this table alone does not yield a usable device credential. (PRD §10, §19)

create table public.device_secrets (
  device_id   text primary key references public.devices (id) on delete cascade,
  ciphertext  text    not null,
  iv          text    not null,
  key_version integer not null default 1,
  created_at  timestamptz not null default now(),
  rotated_at  timestamptz
);

-- --------------------------------------------------------- device_nonces ----
-- Replay defence for device HMAC requests (PROTOCOL §3). Swept by
-- wolf_sweep_nonces(); rows outside the 120 s window are meaningless.

create table public.device_nonces (
  device_id text not null,
  nonce     text not null,
  seen_at   timestamptz not null default now(),
  primary key (device_id, nonce)
);

create index device_nonces_seen_idx on public.device_nonces (seen_at);

-- --------------------------------------------------------------- commands ----

create table public.commands (
  id               uuid primary key,
  pc_id            text not null references public.pcs (id) on delete cascade,
  user_id          uuid not null references public.users (id) on delete cascade,
  issuer_device_id text          references public.devices (id) on delete set null,
  target           wolf_device_type    not null,
  command          wolf_command        not null,
  status           wolf_command_status not null default 'QUEUED',
  nonce            text not null,
  signature        text not null,
  issued_at        timestamptz not null,
  expires_at       timestamptz not null,
  created_at       timestamptz not null default now(),
  sent_at          timestamptz,
  acknowledged_at  timestamptz,
  completed_at     timestamptz,
  error            text,
  constraint commands_target_valid check (target in ('CONTROLLER', 'AGENT')),
  constraint commands_window_valid check (expires_at > issued_at)
);

-- The hot path: "give me this pc's queued commands for this role".
create index commands_dispatch_idx
  on public.commands (pc_id, target, status, created_at)
  where status in ('QUEUED', 'SENT');

create index commands_history_idx on public.commands (pc_id, created_at desc);
create index commands_nonce_idx   on public.commands (issuer_device_id, nonce);

-- One command per (phone, nonce): a replayed envelope cannot become a second row.
create unique index commands_issuer_nonce_uniq
  on public.commands (issuer_device_id, nonce)
  where issuer_device_id is not null;

-- ------------------------------------------------------------- pc_status ----
-- Controller state and Windows state are separate signals. The ESP32 cannot
-- know that Windows is running. (PRD §28, PROTOCOL §7)

create table public.pc_status (
  pc_id                text primary key references public.pcs (id) on delete cascade,
  controller_online    boolean not null default false,
  windows_agent_online boolean not null default false,
  windows_state        wolf_windows_state not null default 'UNKNOWN',
  session_state        wolf_session_state not null default 'NONE',
  controller_last_seen timestamptz,
  agent_last_seen      timestamptz,
  last_command         wolf_command,
  last_command_status  wolf_command_status,
  updated_at           timestamptz not null default now()
);

-- ------------------------------------------------------- login_challenges ----

create table public.login_challenges (
  id                  uuid primary key default gen_random_uuid(),
  pc_id               text not null references public.pcs (id) on delete cascade,
  challenge           text not null,
  created_at          timestamptz not null default now(),
  expires_at          timestamptz not null,
  used_at             timestamptz,
  responder_device_id text references public.devices (id) on delete set null,
  signature           text,
  result              wolf_challenge_result not null default 'PENDING'
);

create index login_challenges_open_idx
  on public.login_challenges (pc_id, created_at desc) where result = 'PENDING';

-- ---------------------------------------------------------- pairing_codes ----

create table public.pairing_codes (
  code        text primary key,
  pc_id       text not null references public.pcs (id) on delete cascade,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null,
  redeemed_at timestamptz,
  redeemed_by uuid references public.users (id) on delete set null
);

-- ------------------------------------------------------------ audit_logs ----

create table public.audit_logs (
  id         bigint generated always as identity primary key,
  user_id    uuid references public.users (id) on delete set null,
  device_id  text,
  pc_id      text,
  action     text not null,
  result     text not null,
  ip         inet,
  created_at timestamptz not null default now(),
  metadata   jsonb not null default '{}'::jsonb
);

create index audit_logs_recent_idx on public.audit_logs (created_at desc);
create index audit_logs_user_idx   on public.audit_logs (user_id, created_at desc);

-- ------------------------------------------- notification_preferences ----

create table public.notification_preferences (
  user_id    uuid primary key references public.users (id) on delete cascade,
  push_token text,
  platform   text not null default 'android',
  prefs      jsonb not null default jsonb_build_object(
               'power_on', true, 'login_screen', true, 'logged_in', true,
               'shutdown', true, 'restart', true, 'hard_reset', true,
               'command_failed', true, 'offline', true),
  updated_at timestamptz not null default now()
);
