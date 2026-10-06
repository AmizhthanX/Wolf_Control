-- WolfPC Remote — server-side maintenance and dispatch helpers
--
-- These run as the service role from Edge Functions or from pg_cron. Keeping
-- the claim-and-expire logic in SQL means a command can never be handed to two
-- devices, even if a device polls twice concurrently.

-- --------------------------------------------------------------------------
-- Expire anything past its window. PRD §13 makes short lifetimes the main
-- defence against a captured envelope, so this must run often: pg_cron every
-- 10 s, and opportunistically at the top of every device-poll.
-- --------------------------------------------------------------------------
create or replace function public.wolf_expire_commands()
returns integer
language sql
security definer
set search_path = public
as $$
  with expired as (
    update public.commands
       set status = 'EXPIRED',
           completed_at = now(),
           error = coalesce(error, 'expired before execution')
     where status in ('QUEUED', 'SENT', 'ACKNOWLEDGED')
       and expires_at < now()
    returning 1
  )
  select count(*)::integer from expired;
$$;

-- --------------------------------------------------------------------------
-- Claim up to max_commands for one device role, atomically.
--
-- FOR UPDATE SKIP LOCKED is what makes a double poll safe: the second caller
-- skips rows the first has already locked rather than handing out the same
-- command twice. A relay pulse is not idempotent, so this matters.
-- --------------------------------------------------------------------------
create or replace function public.wolf_claim_commands(
  target_pc     text,
  target_role   wolf_device_type,
  max_commands  integer default 4
)
returns setof public.commands
language plpgsql
security definer
set search_path = public
as $$
begin
  perform public.wolf_expire_commands();

  return query
  with claimed as (
    select c.id
      from public.commands c
     where c.pc_id  = target_pc
       and c.target = target_role
       and c.status = 'QUEUED'
       and c.expires_at > now()
     order by c.created_at
     limit max_commands
     for update skip locked
  )
  update public.commands c
     set status  = 'SENT',
         sent_at = now()
    from claimed
   where c.id = claimed.id
  returning c.*;
end;
$$;

-- --------------------------------------------------------------------------
-- Forward-only status transition. A device that is unsure whether its ack
-- landed may send it again; a repeat against a terminal row is a no-op rather
-- than an error, so retries are always safe. (PROTOCOL §6)
-- --------------------------------------------------------------------------
create or replace function public.wolf_ack_command(
  command_uuid uuid,
  target_pc    text,
  new_status   wolf_command_status,
  err          text default null
)
returns public.commands
language plpgsql
security definer
set search_path = public
as $$
declare
  row public.commands;
  rank_current integer;
  rank_new     integer;
begin
  select * into row from public.commands
   where id = command_uuid and pc_id = target_pc
   for update;

  if not found then
    return null;
  end if;

  -- terminal states absorb further acks unchanged
  if row.status in ('EXECUTED', 'FAILED', 'EXPIRED') then
    return row;
  end if;

  rank_current := case row.status
                    when 'QUEUED' then 0 when 'SENT' then 1
                    when 'ACKNOWLEDGED' then 2 else 3 end;
  rank_new     := case new_status
                    when 'QUEUED' then 0 when 'SENT' then 1
                    when 'ACKNOWLEDGED' then 2 else 3 end;

  if rank_new < rank_current then
    return row;                       -- never move backwards
  end if;

  update public.commands
     set status          = new_status,
         error           = coalesce(err, error),
         acknowledged_at = case when new_status = 'ACKNOWLEDGED'
                                then now() else acknowledged_at end,
         completed_at    = case when new_status in ('EXECUTED', 'FAILED')
                                then now() else completed_at end
   where id = command_uuid
   returning * into row;

  update public.pc_status
     set last_command = row.command,
         last_command_status = row.status,
         updated_at = now()
   where pc_id = target_pc;

  return row;
end;
$$;

-- --------------------------------------------------------------------------
-- Heartbeat. Writes both the device row and the derived pc_status flags in one
-- place so the two can never drift apart.
--
-- Deliberately does *not* infer windows_state from controller liveness: the
-- ESP32 has no idea whether Windows is up. (PRD §28)
-- --------------------------------------------------------------------------
create or replace function public.wolf_heartbeat(
  dev_id   text,
  fw       text default null,
  meta     jsonb default '{}'::jsonb
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  dev public.devices;
begin
  update public.devices
     set last_seen        = now(),
         firmware_version = coalesce(fw, firmware_version),
         metadata         = metadata || meta
   where id = dev_id
   returning * into dev;

  if not found or dev.pc_id is null then
    return;
  end if;

  insert into public.pc_status (pc_id) values (dev.pc_id)
    on conflict (pc_id) do nothing;

  if dev.device_type = 'CONTROLLER' then
    update public.pc_status
       set controller_online = true,
           controller_last_seen = now(),
           updated_at = now()
     where pc_id = dev.pc_id;
  elsif dev.device_type = 'AGENT' then
    update public.pc_status
       set windows_agent_online = true,
           agent_last_seen = now(),
           updated_at = now()
     where pc_id = dev.pc_id;
  end if;
end;
$$;

-- --------------------------------------------------------------------------
-- Age out liveness. Thresholds match PROTOCOL §7: 15 s for the controller,
-- which polls every 2 s, and 30 s for the agent, which beats every 10 s.
--
-- When the agent drops we go to UNKNOWN, never OFF. Nothing in the MVP can
-- actually observe that the PC lost power, and showing a confident "OFF" that
-- is really "I cannot tell" is how someone ends up hard-resetting a machine
-- that was merely offline.
-- --------------------------------------------------------------------------
create or replace function public.wolf_age_out_presence()
returns void
language sql
security definer
set search_path = public
as $$
  update public.pc_status s
     set controller_online = coalesce(s.controller_last_seen > now() - interval '15 seconds', false),
         windows_agent_online = coalesce(s.agent_last_seen > now() - interval '30 seconds', false),
         windows_state = case
           when coalesce(s.agent_last_seen > now() - interval '30 seconds', false) then s.windows_state
           when s.windows_state in ('OFF', 'SHUTTING_DOWN') then 'OFF'::wolf_windows_state
           else 'UNKNOWN'::wolf_windows_state
         end,
         session_state = case
           when coalesce(s.agent_last_seen > now() - interval '30 seconds', false) then s.session_state
           else 'NONE'::wolf_session_state
         end,
         updated_at = case
           when s.controller_online <> coalesce(s.controller_last_seen > now() - interval '15 seconds', false)
             or s.windows_agent_online <> coalesce(s.agent_last_seen > now() - interval '30 seconds', false)
           then now() else s.updated_at
         end;
$$;

-- --------------------------------------------------------------------------
-- Housekeeping
-- --------------------------------------------------------------------------
create or replace function public.wolf_sweep_nonces()
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.device_nonces where seen_at < now() - interval '10 minutes';
  update public.login_challenges
     set result = 'EXPIRED'
   where result = 'PENDING' and expires_at < now();
  delete from public.pairing_codes
   where expires_at < now() - interval '1 day' and redeemed_at is null;
$$;

-- --------------------------------------------------------------------------
-- Rate limit (PRD §24). Counts this user's commands in a sliding window.
-- --------------------------------------------------------------------------
create or replace function public.wolf_recent_command_count(
  uid           uuid,
  window_secs   integer default 60
)
returns integer
language sql
stable
security definer
set search_path = public
as $$
  select count(*)::integer from public.commands
   where user_id = uid
     and created_at > now() - make_interval(secs => window_secs);
$$;

-- Lock these down to the Edge Functions.
--
-- Revoking from PUBLIC also removes service_role's implicit grant, because
-- service_role inherits EXECUTE through PUBLIC and holds no direct grant of its
-- own. So each revoke is paired with an explicit grant below; without that
-- pairing every function call would fail with "permission denied", and it
-- would fail at runtime rather than at migration time.
revoke execute on function public.wolf_expire_commands()                from public, anon, authenticated;
revoke execute on function public.wolf_claim_commands(text, wolf_device_type, integer) from public, anon, authenticated;
revoke execute on function public.wolf_ack_command(uuid, text, wolf_command_status, text) from public, anon, authenticated;
revoke execute on function public.wolf_heartbeat(text, text, jsonb)     from public, anon, authenticated;
revoke execute on function public.wolf_age_out_presence()               from public, anon, authenticated;
revoke execute on function public.wolf_sweep_nonces()                   from public, anon, authenticated;
revoke execute on function public.wolf_recent_command_count(uuid, integer) from public, anon, authenticated;

grant execute on function public.wolf_expire_commands()                              to service_role;
grant execute on function public.wolf_claim_commands(text, wolf_device_type, integer) to service_role;
grant execute on function public.wolf_ack_command(uuid, text, wolf_command_status, text) to service_role;
grant execute on function public.wolf_heartbeat(text, text, jsonb)                   to service_role;
grant execute on function public.wolf_age_out_presence()                             to service_role;
grant execute on function public.wolf_sweep_nonces()                                 to service_role;
grant execute on function public.wolf_recent_command_count(uuid, integer)            to service_role;
