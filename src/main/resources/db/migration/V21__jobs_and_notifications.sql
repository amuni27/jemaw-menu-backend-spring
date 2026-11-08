-- AGF SUB-R2-08: the durable job queue and the owner-notice outbox (BD-5, BD-18). Both tables are created here, once;
-- SUB-R2-09 uses notification as it is. Never edited after it is committed. Additive: no existing row changes.

create table job (
    id               varchar(36)  not null,
    type             varchar(64)  not null,
    key              varchar(255) not null, -- the identity of one business intent
    payload_hash     varchar(64)  not null, -- an existing key with another hash is a conflict, never a silent drop
    status           varchar(16)  not null default 'PENDING',
    claims           integer      not null default 0, -- counted at claim, so a worker that dies still counts
    claim_token      varchar(36), -- fencing: new per claim; every completion must match it
    lease_until      timestamp(6) with time zone,
    run_at           timestamp(6) with time zone not null default now(),
    first_attempt_at timestamp(6) with time zone,
    last_error       text,
    last_error_kind  varchar(16),
    constraint pk_job primary key (id),
    constraint uk_job_type_key unique (type, key),
    constraint ck_job_status check (status in ('PENDING', 'RUNNING', 'DONE', 'DEAD')),
    constraint ck_job_error_kind check (last_error_kind in ('TRANSIENT', 'PERMANENT')),
    constraint ck_job_claims check (claims >= 0),
    constraint ck_job_running_claim check (status <> 'RUNNING' or (claim_token is not null and lease_until is not null))
);

create index ix_job_pending_run_at on job (run_at) where status = 'PENDING';
create index ix_job_running_lease on job (lease_until) where status = 'RUNNING';

create table notification (
    id          varchar(36)  not null,
    business_id varchar(255) not null,
    kind        varchar(64)  not null,
    key         varchar(255) not null,
    channel     varchar(16)  not null,
    kind_class  varchar(16)  not null, -- PAYMENT_REQUEST notices can be paused on their own (I1)
    status      varchar(16)  not null,
    attempts    integer      not null default 0,
    constraint pk_notification primary key (id),
    constraint uk_notification_restaurant_kind_key unique (business_id, kind, key),
    constraint fk_notification_business foreign key (business_id) references business (id),
    constraint ck_notification_channel check (channel in ('IN_APP', 'EMAIL')),
    constraint ck_notification_kind_class check (kind_class in ('PAYMENT_REQUEST', 'ESSENTIAL')),
    constraint ck_notification_attempts check (attempts >= 0)
);
