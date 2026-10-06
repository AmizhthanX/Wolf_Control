-- WolfPC Remote — realtime publication and scheduled maintenance (PRD §21)
--
-- The app listens to changes on its own rows only. RLS is what makes that safe:
-- Realtime evaluates the same policies as a select, so a subscriber sees only
-- the pcs it owns. There is no public channel and no broadcast of envelopes.

alter publication supabase_realtime add table public.commands;
alter publication supabase_realtime add table public.pc_status;
alter publication supabase_realtime add table public.devices;

-- Realtime needs the full old row to evaluate policies on update and delete.
alter table public.commands  replica identity full;
alter table public.pc_status replica identity full;
alter table public.devices   replica identity full;

-- --------------------------------------------------------------------------
-- Scheduled maintenance.
--
-- pg_cron's finest granularity is one minute, and an envelope that lives 15 s
-- cannot wait a minute to be marked EXPIRED. So correctness does not depend on
-- these jobs: wolf_claim_commands() re-checks expires_at on every poll and
-- wolf_expire_commands() runs there too. These jobs only stop dead rows from
-- accumulating and keep presence honest when nobody is polling.
--
-- If the pg_cron extension is unavailable on your plan, drop this whole block.
-- The system still behaves correctly, it just leaves stale QUEUED rows visible
-- in history until the next poll.
-- --------------------------------------------------------------------------
do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron;

    perform cron.schedule('wolf-expire-commands', '* * * * *',
                          $job$ select public.wolf_expire_commands(); $job$);
    perform cron.schedule('wolf-age-out-presence', '* * * * *',
                          $job$ select public.wolf_age_out_presence(); $job$);
    perform cron.schedule('wolf-sweep',            '*/5 * * * *',
                          $job$ select public.wolf_sweep_nonces(); $job$);
  else
    raise notice 'pg_cron not available; relying on per-poll maintenance only';
  end if;
end;
$$;
