-- AGF SUB-R2-08: the durable job queue and the owner-notice outbox (BD-5, BD-18). Both tables are created here, once;
-- SUB-R2-09 uses notification as it is. Additive: no existing row changes.

create table job (
    id               varchar(36)  not null,
    type             varchar(64)  not null,
    key              varchar(255) not null, -- the identity of one business intent
    payload          jsonb        not null,
    payload_hash     varchar(64)  not null, -- SHA-256 of the canonical payload; an existing key with another hash is a conflict
    status           varchar(16)  not null default 'PENDING',
    claims           integer      not null default 0, -- counted at claim, so a worker that dies still counts
    claim_token      varchar(36), -- fencing: new per claim; every completion must match it
    lease_until      timestamp(6) with time zone,
    run_at           timestamp(6) with time zone not null default now(),
    first_attempt_at timestamp(6) with time zone,
    created_at       timestamp(6) with time zone not null default now(),
    finished_at      timestamp(6) with time zone,
    last_error       text,
    last_error_kind  varchar(16),
    constraint pk_job primary key (id),
    constraint uk_job_type_key unique (type, key),
    constraint ck_job_status check (status in ('PENDING', 'RUNNING', 'DONE', 'DEAD')),
    constraint ck_job_error_kind check (last_error_kind in ('TRANSIENT', 'PERMANENT')),
    constraint ck_job_claims check (claims >= 0),
    constraint ck_job_running_claim check (status <> 'RUNNING' or (claim_token is not null and lease_until is not null)),
    constraint ck_job_finished check ((status in ('DONE', 'DEAD')) = (finished_at is not null))
);

create index ix_job_pending_run_at on job (run_at) where status = 'PENDING';
create index ix_job_running_lease on job (lease_until) where status = 'RUNNING';
create index ix_job_done_finished_at on job (finished_at) where status = 'DONE';

create table notification (
    id            varchar(36)  not null,
    business_id   varchar(255) not null,
    kind          varchar(64)  not null,
    key           varchar(255) not null,
    channel       varchar(16)  not null, -- one row per channel: each keeps its own delivery state
    kind_class    varchar(16)  not null, -- PAYMENT_REQUEST notices can be paused on their own (I1)
    status        varchar(16)  not null default 'PENDING',
    attempts      integer      not null default 0,
    template_data jsonb        not null default '{}'::jsonb,
    created_at    timestamp(6) with time zone not null default now(),
    updated_at    timestamp(6) with time zone not null default now(),
    constraint pk_notification primary key (id),
    constraint uk_notification_restaurant_kind_key_channel unique (business_id, kind, key, channel),
    constraint fk_notification_business foreign key (business_id) references business (id),
    constraint ck_notification_channel check (channel in ('IN_APP', 'EMAIL')),
    constraint ck_notification_kind_class check (kind_class in ('PAYMENT_REQUEST', 'ESSENTIAL')),
    constraint ck_notification_status check (status in ('PENDING', 'SENT', 'FAILED', 'READ')),
    constraint ck_notification_email_not_read check (not (channel = 'EMAIL' and status = 'READ')),
    constraint ck_notification_attempts check (attempts >= 0)
);

create function notification_status_guard() returns trigger language plpgsql as $$
begin
    if new.channel is distinct from old.channel then raise exception 'notification channel can''t be changed'; end if;
    if new.status is distinct from old.status then
        if new.status = 'READ' and new.channel <> 'IN_APP' then raise exception 'only an in-app notice can be read'; end if;
        if not ((old.status = 'PENDING' and new.status in ('SENT', 'FAILED'))
                or (old.status = 'FAILED' and new.status = 'PENDING')
                or (old.status = 'SENT' and new.status = 'READ')) then
            raise exception 'notification status can''t change from % to %', old.status, new.status;
        end if;
    end if;
    return new;
end $$;

create trigger notification_status_guard before update on notification
    for each row execute function notification_status_guard();
