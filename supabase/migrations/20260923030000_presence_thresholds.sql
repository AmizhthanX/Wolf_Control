-- Widen the liveness thresholds to match slower polling.
--
-- The original 15 s and 30 s windows assumed the PRD's 2 s controller poll and
-- a 10 s agent heartbeat. At that rate the system burns roughly 2.4 million
-- Edge Function invocations a month against a 500,000 free-tier ceiling —
-- about five times over, exhausting the month in under a week, after which
-- nothing works until it rolls over.
--
-- The polls are being slowed to fit (controller 10 s, agent 30 s). These
-- thresholds have to widen with them or every device flaps to offline between
-- its own polls. Each is now roughly three missed reports, so a single dropped
-- request is invisible and a genuinely dead device is still noticed quickly.
--
-- Latency is barely affected in practice, because the LAN path carries
-- everything while you are at home: a command sent from the sofa goes straight
-- to the device and never waits for a poll at all. The slower interval only
-- costs anything when you are away, where waiting a few seconds for a PC to
-- start is not the difference that matters.

create or replace function public.wolf_age_out_presence()
returns void
language sql
security definer
set search_path = public
as $$
  update public.pc_status s
     set controller_online = coalesce(s.controller_last_seen > now() - interval '35 seconds', false),
         windows_agent_online = coalesce(s.agent_last_seen > now() - interval '100 seconds', false),
         windows_state = case
           when coalesce(s.agent_last_seen > now() - interval '100 seconds', false) then s.windows_state
           when s.windows_state in ('OFF', 'SHUTTING_DOWN') then 'OFF'::wolf_windows_state
           else 'UNKNOWN'::wolf_windows_state
         end,
         session_state = case
           when coalesce(s.agent_last_seen > now() - interval '100 seconds', false) then s.session_state
           else 'NONE'::wolf_session_state
         end,
         updated_at = case
           when s.controller_online <> coalesce(s.controller_last_seen > now() - interval '35 seconds', false)
             or s.windows_agent_online <> coalesce(s.agent_last_seen > now() - interval '100 seconds', false)
           then now() else s.updated_at
         end;
$$;

revoke execute on function public.wolf_age_out_presence() from public, anon, authenticated;
grant  execute on function public.wolf_age_out_presence() to service_role;
