-- BeeKeep cloud sync + private inspection photo storage.
-- Run this in Supabase SQL Editor after the base BeeKeep cloud migration.

create table if not exists public.beekeep_documents (
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

drop policy if exists "BeeKeep users can read their documents" on public.beekeep_documents;
create policy "BeeKeep users can read their documents"
on public.beekeep_documents for select
to authenticated
using (auth.uid() = user_id);

drop policy if exists "BeeKeep users can insert their documents" on public.beekeep_documents;
create policy "BeeKeep users can insert their documents"
on public.beekeep_documents for insert
to authenticated
with check (auth.uid() = user_id);

drop policy if exists "BeeKeep users can update their documents" on public.beekeep_documents;
create policy "BeeKeep users can update their documents"
on public.beekeep_documents for update
to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

alter table public.beekeep_documents replica identity full;

do $$
begin
  if not exists (
    select 1
    from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'beekeep_documents'
  ) then
    alter publication supabase_realtime add table public.beekeep_documents;
  end if;
end $$;

insert into storage.buckets (id, name, public)
values ('inspection-photos', 'inspection-photos', false)
on conflict (id) do nothing;

drop policy if exists "BeeKeep photo read" on storage.objects;
create policy "BeeKeep photo read"
on storage.objects for select
to authenticated
using (
  bucket_id = 'inspection-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
);

drop policy if exists "BeeKeep photo upload" on storage.objects;
create policy "BeeKeep photo upload"
on storage.objects for insert
to authenticated
with check (
  bucket_id = 'inspection-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
);

drop policy if exists "BeeKeep photo update" on storage.objects;
create policy "BeeKeep photo update"
on storage.objects for update
to authenticated
using (
  bucket_id = 'inspection-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
)
with check (
  bucket_id = 'inspection-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
);

drop policy if exists "BeeKeep photo delete" on storage.objects;
create policy "BeeKeep photo delete"
on storage.objects for delete
to authenticated
using (
  bucket_id = 'inspection-photos'
  and (storage.foldername(name))[1] = auth.uid()::text
);
