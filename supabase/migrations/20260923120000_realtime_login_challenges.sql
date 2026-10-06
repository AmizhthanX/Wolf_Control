-- Let the phone hear a login prompt instead of waiting to notice one.
--
-- Phase 8's provider-initiated flow is: somebody clicks the Wolf tile at the
-- login screen, the agent opens a challenge, and the phone has to ask its owner
-- to approve it. The challenge lives 60 seconds. Anything that finds out about
-- it by polling either burns most of that window or burns Edge Function
-- invocations to avoid doing so, and the free-tier budget in docs/DEPLOY.md is
-- the reason the rest of this system already runs on 30 second timers.
--
-- Realtime costs nothing per message, so the prompt arrives in about a second
-- and the quota is untouched. The app still reads the row over PostgREST once
-- it is told to look: the notification says "something changed", never what.
--
-- RLS is what makes this safe. Realtime evaluates the same policies as a
-- select, and login_challenges_select_own already confines a subscriber to
-- challenges for pcs they own. Nothing in the row is a secret in any case - the
-- challenge is 32 random bytes that are worthless without a signature from a
-- key held in a phone's Keystore.

alter publication supabase_realtime add table public.login_challenges;

-- Realtime needs the full old row to evaluate policies on update and delete.
alter table public.login_challenges replica identity full;
