alter table organization add column staff_view_all boolean not null default false;

create table care_group (
    id uuid primary key,
    org_id uuid not null references organization(id),
    name varchar(100) not null,
    unique (id, org_id)
);
create index care_group_org_idx on care_group(org_id);

create table participant (
    id uuid primary key,
    org_id uuid not null references organization(id),
    group_id uuid,
    display_name varchar(100) not null,
    foreign key (group_id, org_id) references care_group(id, org_id)
);
create index participant_org_idx on participant(org_id, id);

create table staff_group_assignment (
    id uuid primary key,
    org_id uuid not null references organization(id),
    user_id uuid not null,
    group_id uuid not null,
    foreign key (org_id, user_id) references membership(org_id, user_id),
    foreign key (group_id, org_id) references care_group(id, org_id),
    unique (group_id, user_id)
);
create index staff_group_assignment_org_idx on staff_group_assignment(org_id, user_id);

alter table care_group enable row level security;
alter table care_group force row level security;
create policy care_group_tenant on care_group
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);
alter table participant enable row level security;
alter table participant force row level security;
create policy participant_tenant on participant
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);
alter table staff_group_assignment enable row level security;
alter table staff_group_assignment force row level security;
create policy staff_group_assignment_tenant on staff_group_assignment
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

grant select, insert, update, delete on care_group, participant, staff_group_assignment to "${applicationUser}";
