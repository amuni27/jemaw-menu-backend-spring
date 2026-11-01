-- AGF E4-M1: append-only audit of important Super Admin actions (bootstrap, sign-in). Never passwords, tokens or other
-- secrets; actors are stored as a user id (if known) and a masked label. No foreign key, so history survives account
-- changes. Rows can't be changed or deleted (trigger). V1-V10 untouched.
create table admin_audit_event (
    id            varchar(36)  not null,
    occurred_at   timestamp(6) with time zone not null,
    actor_user_id varchar(255),
    actor_label   varchar(255) not null,
    action        varchar(64)  not null,
    target_type   varchar(64),
    target_id     varchar(255),
    outcome       varchar(16)  not null,
    detail        varchar(500),
    constraint pk_admin_audit_event primary key (id),
    constraint ck_admin_audit_event_outcome check (outcome in ('SUCCESS', 'FAILURE'))
);
create index ix_admin_audit_event_occurred_at on admin_audit_event (occurred_at desc);

create function admin_audit_event_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'admin_audit_event is append-only';
end
$$;

create trigger admin_audit_event_no_change before update or delete on admin_audit_event
    for each row execute function admin_audit_event_append_only();
