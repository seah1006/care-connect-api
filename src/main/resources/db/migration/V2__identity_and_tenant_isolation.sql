create table organization (
    id uuid primary key,
    name varchar(255) not null
);

create table staff_user (
    id uuid primary key,
    username varchar(255) not null unique,
    password_hash varchar(255) not null,
    created_at timestamptz not null
);

create table membership (
    id uuid primary key,
    org_id uuid not null references organization(id),
    user_id uuid not null references staff_user(id),
    role varchar(20) not null check (role in ('ORG_ADMIN', 'STAFF')),
    unique (org_id, user_id)
);
create index membership_org_idx on membership(org_id);
alter table membership enable row level security;
alter table membership force row level security;
create policy membership_tenant on membership
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

-- Global authentication records: lookups precede establishment of tenant context.
create table refresh_token (
    id uuid primary key,
    family_id uuid not null,
    user_id uuid not null references staff_user(id),
    org_id uuid not null references organization(id),
    token_hash varchar(64) not null unique check (token_hash ~ '^[0-9a-f]{64}$'),
    parent_id uuid references refresh_token(id),
    status varchar(10) not null check (status in ('ACTIVE', 'USED', 'REVOKED')),
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    used_at timestamptz
);
create index refresh_token_org_idx on refresh_token(org_id);
create index refresh_token_family_idx on refresh_token(family_id);
create unique index refresh_token_single_active on refresh_token(family_id) where status = 'ACTIVE';
create unique index refresh_token_single_child on refresh_token(parent_id) where parent_id is not null;

-- The operator provisions DB_USERNAME as a separate NOSUPERUSER NOBYPASSRLS login.
grant usage on schema public to "${applicationUser}";
grant select, insert, update, delete on organization, staff_user, membership, refresh_token to "${applicationUser}";
