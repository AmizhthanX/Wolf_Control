-- Let the app know whether phone unlock actually exists on this PC.
--
-- Without this the app offers an UNLOCK PC button at the login screen on every
-- installation, including the overwhelming majority that have no credential
-- provider installed (PRD §31 makes Phase 8 optional). Tapping it costs a
-- biometric prompt and returns "phone login is not installed on this PC",
-- which reads as a broken app rather than an absent feature.
--
-- Reported by the agent, which is the only thing that can know: it owns the
-- named pipe the credential provider connects to.

alter table public.pc_status
  add column if not exists phone_login_available boolean not null default false;

comment on column public.pc_status.phone_login_available is
  'True while a Wolf credential provider is connected to the agent''s login pipe.';
