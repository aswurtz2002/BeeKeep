-- Ensure invitation token cryptography resolves in Supabase's extensions schema.
-- These functions are SECURITY DEFINER and use a deliberately fixed search path.

alter function public.beekeep_create_workspace_invitation(uuid, text, text)
    set search_path = public, extensions, pg_temp;

alter function public.beekeep_accept_workspace_invitation(text)
    set search_path = public, extensions, pg_temp;
