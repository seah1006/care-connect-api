create table tenant_probe (
    id uuid primary key,
    org_id uuid not null references organization(id),
    label varchar(255) not null
);
create index tenant_probe_org_idx on tenant_probe(org_id);
alter table tenant_probe enable row level security;
alter table tenant_probe force row level security;
create policy tenant_probe_tenant on tenant_probe
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);
grant select, insert, update, delete on tenant_probe to "${applicationUser}";
grant select on flyway_schema_history to "${applicationUser}";
