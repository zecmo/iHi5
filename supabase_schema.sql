-- ============================================================
-- iHi5 Supabase Schema
-- Run this in the Supabase SQL Editor (supabase.com → SQL Editor)
-- ============================================================

-- ────────────────────────────────────────────────────────────
-- USERS
-- ────────────────────────────────────────────────────────────
create table if not exists users (
    id               uuid primary key,
    username         text unique not null,
    email            text default '',
    last_login_at    bigint default 0,
    hand_raised      boolean default false,
    raised_hand_at   bigint default 0,
    current_session  text default ''
);

create index if not exists users_username_idx on users (username);
create index if not exists users_hand_raised_idx on users (hand_raised);

alter table users enable row level security;

-- Anyone can read users (needed for lobby / presence)
create policy "users_select" on users for select using (true);
-- Only the owner can insert/update their own row
create policy "users_insert" on users for insert with check (auth.uid() = id);
create policy "users_update" on users for update using (auth.uid() = id);

-- ────────────────────────────────────────────────────────────
-- FRIENDSHIPS  (normalised junction table)
-- ────────────────────────────────────────────────────────────
create table if not exists friendships (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid references users(id) on delete cascade not null,
    friend_id  uuid references users(id) on delete cascade not null,
    created_at timestamptz default now(),
    unique (user_id, friend_id)
);

create index if not exists friendships_user_idx   on friendships (user_id);
create index if not exists friendships_friend_idx on friendships (friend_id);

alter table friendships enable row level security;

create policy "friendships_select" on friendships for select using (
    auth.uid() = user_id or auth.uid() = friend_id
);
create policy "friendships_insert" on friendships for insert with check (
    auth.uid() = user_id
);
create policy "friendships_delete" on friendships for delete using (
    auth.uid() = user_id
);

-- ────────────────────────────────────────────────────────────
-- HIGH FIVE SESSIONS
-- ────────────────────────────────────────────────────────────
create table if not exists high_five_sessions (
    id                  uuid primary key default gen_random_uuid(),
    initiator_id        uuid references users(id) on delete cascade not null,
    initiator_username  text not null,
    partner_id          uuid references users(id) on delete cascade,
    partner_username    text default '',
    initiator_timestamp bigint default 0,
    partner_timestamp   bigint default 0,
    last_updated        bigint default 0,
    completed           boolean default false,
    quality             text default ''
);

create index if not exists sessions_initiator_idx on high_five_sessions (initiator_id);
create index if not exists sessions_partner_idx   on high_five_sessions (partner_id);

alter table high_five_sessions enable row level security;

create policy "sessions_select" on high_five_sessions for select using (
    auth.uid() = initiator_id or auth.uid() = partner_id
);
create policy "sessions_insert" on high_five_sessions for insert with check (
    auth.uid() = initiator_id
);
create policy "sessions_update" on high_five_sessions for update using (
    auth.uid() = initiator_id or auth.uid() = partner_id
);
create policy "sessions_delete" on high_five_sessions for delete using (
    auth.uid() = initiator_id
);

-- ────────────────────────────────────────────────────────────
-- HIGH FIVES  (individual tap events)
-- ────────────────────────────────────────────────────────────
create table if not exists high_fives (
    id                  uuid primary key default gen_random_uuid(),
    initiator_id        uuid references users(id) on delete cascade not null,
    receiver_id         uuid references users(id) on delete cascade not null,
    initiator_timestamp bigint default 0,
    receiver_timestamp  bigint default 0,
    status              text default 'pending', -- pending | matched | completed | expired
    quality             float default 0,
    created_at          timestamptz default now()
);

create index if not exists high_fives_initiator_idx on high_fives (initiator_id);
create index if not exists high_fives_receiver_idx  on high_fives (receiver_id);
create index if not exists high_fives_status_idx    on high_fives (status);

alter table high_fives enable row level security;

create policy "high_fives_select" on high_fives for select using (
    auth.uid() = initiator_id or auth.uid() = receiver_id
);
create policy "high_fives_insert" on high_fives for insert with check (
    auth.uid() = initiator_id
);
create policy "high_fives_update" on high_fives for update using (
    auth.uid() = initiator_id or auth.uid() = receiver_id
);

-- ────────────────────────────────────────────────────────────
-- NOTIFICATIONS
-- ────────────────────────────────────────────────────────────
create table if not exists notifications (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid references users(id) on delete cascade not null,
    type        text not null,
    sender_id   uuid references users(id) on delete cascade,
    sender_name text default '',
    data        jsonb default '{}',
    read        boolean default false,
    created_at  timestamptz default now()
);

create index if not exists notifications_user_idx on notifications (user_id, read);

alter table notifications enable row level security;

create policy "notifications_select" on notifications for select using (
    auth.uid() = user_id
);
create policy "notifications_insert" on notifications for insert with check (true);
create policy "notifications_update" on notifications for update using (
    auth.uid() = user_id
);

-- ────────────────────────────────────────────────────────────
-- ENABLE REALTIME on tables we subscribe to
-- ────────────────────────────────────────────────────────────
alter publication supabase_realtime add table users;
alter publication supabase_realtime add table high_five_sessions;
alter publication supabase_realtime add table high_fives;
alter publication supabase_realtime add table notifications;
