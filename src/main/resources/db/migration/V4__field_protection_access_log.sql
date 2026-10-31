create table access_log (
    id uuid primary key,
    org_id uuid not null references organization(id),
    actor_user_id uuid not null,
    action varchar(20) not null check (action in ('READ', 'LIST', 'UPDATE', 'DOWNLOAD')),
    resource_type varchar(50) not null,
    resource_id uuid,
    outcome varchar(20) not null check (outcome in ('ALLOWED', 'DENIED', 'NOT_FOUND')),
    occurred_at timestamptz not null,
    client_ip varchar(45),
    request_id uuid not null
);
create index access_log_org_time_idx on access_log(org_id, occurred_at);
alter table access_log enable row level security;
alter table access_log force row level security;
create policy access_log_tenant on access_log
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

revoke all on access_log from "${applicationUser}";
grant select, insert on access_log to "${applicationUser}";

create function protect_access_log() returns trigger language plpgsql as $$
begin
    if TG_OP = 'DELETE' then
        if OLD.occurred_at < current_timestamp - interval '2 years' then
            return OLD;
        end if;
    end if;
    raise exception 'Access log is append only' using errcode = '42501';
end;
$$;
create trigger access_log_no_change before update or delete on access_log
    for each row execute function protect_access_log();
create trigger access_log_no_truncate before truncate on access_log
    for each statement execute function protect_access_log();
