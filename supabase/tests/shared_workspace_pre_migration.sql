-- Minimal Supabase-like schema for testing the Stage 1 migration in a disposable PostgreSQL database.
-- This is test-only and is never applied to the user's actual Supabase project.

do $roles$
begin
    create role anon nologin;
exception when duplicate_object then null;
end;
$roles$;

do $roles$
begin
    create role authenticated nologin;
exception when duplicate_object then null;
end;
$roles$;

create schema if not exists auth;
create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

create table auth.users (
    id uuid primary key,
    email text unique,
    raw_user_meta_data jsonb not null default '{}'::jsonb,
    raw_app_meta_data jsonb not null default '{}'::jsonb
);

create or replace function auth.uid()
returns uuid
language sql
stable
as $function$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid;
$function$;

grant usage on schema auth to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;
grant usage on schema public to authenticated;

-- Start with the existing per-user cloud table and its current RLS model.
create table public.beekeep_documents (
    user_id uuid not null references auth.users(id) on delete cascade,
    entity_type text not null,
    entity_id bigint not null,
    payload text not null,
    updated_at bigint not null,
    deleted boolean not null default false,
    device_id text not null default 'android',
    primary key (user_id, entity_type, entity_id)
);

alter table public.beekeep_documents enable row level security;
grant select, insert, update on public.beekeep_documents to authenticated;

create policy "BeeKeep users can read their documents"
on public.beekeep_documents for select
to authenticated
using (auth.uid() = user_id);

create policy "BeeKeep users can insert their documents"
on public.beekeep_documents for insert
to authenticated
with check (auth.uid() = user_id);

create policy "BeeKeep users can update their documents"
on public.beekeep_documents for update
to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

-- Existing users and records prove the migration backfills without mixing accounts.
insert into auth.users (id, email, raw_user_meta_data) values
    ('00000000-0000-0000-0000-000000000001', 'owner-one@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000002', 'owner-two@example.test', '{}'::jsonb);

insert into public.beekeep_documents (user_id, entity_type, entity_id, payload, updated_at)
values
    ('00000000-0000-0000-0000-000000000001', 'hive', 1, '{"id":1,"number":"1"}', 1000),
    ('00000000-0000-0000-0000-000000000002', 'hive', 1, '{"id":1,"number":"1"}', 1000);
