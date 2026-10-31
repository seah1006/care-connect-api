create table sensitive_probe (
    id uuid primary key,
    org_id uuid not null references organization(id),
    secret text
);
alter table sensitive_probe enable row level security;
alter table sensitive_probe force row level security;
create policy sensitive_probe_tenant on sensitive_probe
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);
grant select, insert, update, delete on sensitive_probe to "${applicationUser}";
