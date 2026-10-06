-- Lock the interactive console without logging out.
alter type public.wolf_command add value if not exists 'LOCK';
