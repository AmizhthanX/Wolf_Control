-- WolfPC Remote — row level security (PRD §24)
--
-- Rule of thumb applied throughout: the phone may *read* its own world and may
-- write only its own preferences. Anything that authorises a physical action
-- goes through an Edge Function, because only the function can verify the
-- ECDSA envelope signature before a command row exists. A stolen JWT alone must
-- not be able to insert a command.

alter table public.users                     enable row level security;
alter table public.pcs                       enable row level security;
alter table public.devices                   enable row level security;
alter table public.device_keys               enable row level security;
alter table public.device_secrets            enable row level security;
alter table public.device_nonces             enable row level security;
alter table public.commands                  enable row level security;
alter table public.pc_status                 enable row level security;
alter table public.login_challenges          enable row level security;
alter table public.pairing_codes             enable row level security;
alter table public.audit_logs                enable row level security;
alter table public.notification_preferences  enable row level security;

-- device_secrets and device_nonces get no policies at all. With RLS enabled and
-- zero policies, every non-service role sees an empty table and every write is
-- refused. That is deliberate: these are reachable only by Edge Functions
-- holding the service role key.

-- Is this pc owned by the caller?
create or replace function public.wolf_owns_pc(target_pc text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.pcs p
    where p.id = target_pc and p.owner_id = auth.uid()
  );
$$;

-- ---------------------------------------------------------------- users ----

create policy users_select_self on public.users
  for select to authenticated
  using (id = auth.uid());

create policy users_update_self on public.users
  for update to authenticated
  using (id = auth.uid())
  with check (id = auth.uid());

-- ------------------------------------------------------------------ pcs ----

create policy pcs_select_own on public.pcs
  for select to authenticated
  using (owner_id = auth.uid());

create policy pcs_insert_own on public.pcs
  for insert to authenticated
  with check (owner_id = auth.uid());

create policy pcs_update_own on public.pcs
  for update to authenticated
  using (owner_id = auth.uid())
  with check (owner_id = auth.uid());

-- -------------------------------------------------------------- devices ----

create policy devices_select_own on public.devices
  for select to authenticated
  using (owner_id = auth.uid());

-- The app may rename a device or revoke it, but may not change its type,
-- ownership or pairing state — those move only through pair-device.
create policy devices_update_own on public.devices
  for update to authenticated
  using (owner_id = auth.uid())
  with check (owner_id = auth.uid());

-- ----------------------------------------------------------- device_keys ----

create policy device_keys_select_own on public.device_keys
  for select to authenticated
  using (exists (
    select 1 from public.devices d
    where d.id = device_keys.device_id and d.owner_id = auth.uid()
  ));

-- Revoking a key is the one write the app is trusted with: it is a
-- fail-safe direction, and a user who has lost a phone should not need
-- the network round trip of a function call to cut it off.
create policy device_keys_revoke_own on public.device_keys
  for update to authenticated
  using (exists (
    select 1 from public.devices d
    where d.id = device_keys.device_id and d.owner_id = auth.uid()
  ))
  with check (exists (
    select 1 from public.devices d
    where d.id = device_keys.device_id and d.owner_id = auth.uid()
  ));

-- -------------------------------------------------------------- commands ----
-- Read only. Insert and status transitions belong to Edge Functions.

create policy commands_select_own on public.commands
  for select to authenticated
  using (public.wolf_owns_pc(pc_id));

-- ------------------------------------------------------------- pc_status ----

create policy pc_status_select_own on public.pc_status
  for select to authenticated
  using (public.wolf_owns_pc(pc_id));

-- ------------------------------------------------------- login_challenges ----

create policy login_challenges_select_own on public.login_challenges
  for select to authenticated
  using (public.wolf_owns_pc(pc_id));

-- ---------------------------------------------------------- pairing_codes ----
-- The phone never reads codes: it is handed one out of band, by QR from the
-- agent's pairing window, and redeems it through pair-device. Nothing to select.

-- ------------------------------------------------------------ audit_logs ----

create policy audit_logs_select_own on public.audit_logs
  for select to authenticated
  using (user_id = auth.uid() or public.wolf_owns_pc(pc_id));

-- ------------------------------------------- notification_preferences ----

create policy notif_prefs_all_own on public.notification_preferences
  for all to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid());
