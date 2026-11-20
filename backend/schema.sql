-- 在 Supabase 的 SQL Editor 中运行一次。
-- 每个账号只能读取和修改自己的日历事项。
create table if not exists public.calendar_items (
  id text primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  payload jsonb not null,
  updated_at timestamptz not null default now(),
  constraint calendar_items_id_format check (id ~ '^[0-9a-fA-F]{32}$')
);

create index if not exists calendar_items_user_id_idx
  on public.calendar_items (user_id);

alter table public.calendar_items enable row level security;

revoke all on public.calendar_items from anon;
revoke all on public.calendar_items from authenticated;
grant select, insert, update, delete on public.calendar_items to authenticated;

drop policy if exists calendar_items_select_own on public.calendar_items;
create policy calendar_items_select_own on public.calendar_items
  for select to authenticated
  using ((select auth.uid()) = user_id);

drop policy if exists calendar_items_insert_own on public.calendar_items;
create policy calendar_items_insert_own on public.calendar_items
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

drop policy if exists calendar_items_update_own on public.calendar_items;
create policy calendar_items_update_own on public.calendar_items
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists calendar_items_delete_own on public.calendar_items;
create policy calendar_items_delete_own on public.calendar_items
  for delete to authenticated
  using ((select auth.uid()) = user_id);

