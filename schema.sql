-- Gestion Stock: shared Supabase database
create table if not exists public.app_data (
  collection text not null,
  id text not null,
  payload jsonb not null,
  updated_at timestamptz not null default now(),
  primary key (collection,id)
);

alter table public.app_data enable row level security;

drop policy if exists app_data_select_anon on public.app_data;
drop policy if exists app_data_insert_anon on public.app_data;
drop policy if exists app_data_update_anon on public.app_data;
drop policy if exists app_data_delete_anon on public.app_data;

create policy app_data_select_anon on public.app_data for select to anon using (true);
create policy app_data_insert_anon on public.app_data for insert to anon with check (true);
create policy app_data_update_anon on public.app_data for update to anon using (true) with check (true);
create policy app_data_delete_anon on public.app_data for delete to anon using (true);

grant usage on schema public to anon;
grant select, insert, update, delete on public.app_data to anon;

create or replace function public.touch_app_data_updated_at()
returns trigger language plpgsql as $$
begin new.updated_at=now(); return new; end;
$$;

drop trigger if exists trg_app_data_updated_at on public.app_data;
create trigger trg_app_data_updated_at before update on public.app_data
for each row execute function public.touch_app_data_updated_at();

create index if not exists app_data_collection_idx on public.app_data(collection);
