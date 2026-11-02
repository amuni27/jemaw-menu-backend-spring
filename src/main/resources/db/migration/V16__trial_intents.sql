-- AGF E4-M5 recovery: a durable trial intent, committed with an eligible registration (trials enabled, Setting 1
-- current). It keeps the eligibility decision and the configuration version that made the restaurant eligible, so a
-- trial that wasn't created after the commit (failure or process stop) is retried by the existing daily job. One intent
-- per restaurant, ever. PENDING until resolved: STARTED (linked to the trial) or NOT_STARTED (linked to the current
-- subscription that made a trial impossible). Resolved intents are frozen; intents are never deleted. V1-V15 untouched.
create table trial_intent (
    business_id      varchar(255) not null,
    config_version   integer      not null,
    status           varchar(16)  not null,
    resolution       varchar(32),
    subscription_id  varchar(36),
    failed_attempts  integer      not null,
    last_attempt_at  timestamp(6) with time zone,
    last_error       varchar(64),
    created_at       timestamp(6) with time zone not null,
    resolved_at      timestamp(6) with time zone,
    row_version      integer      not null,
    constraint pk_trial_intent primary key (business_id),
    constraint fk_trial_intent_business foreign key (business_id) references business (id),
    constraint fk_trial_intent_config foreign key (config_version) references monetization_config (version),
    constraint fk_trial_intent_subscription foreign key (subscription_id) references business_subscription (id),
    constraint ck_trial_intent_status check (status in ('PENDING', 'STARTED', 'NOT_STARTED')),
    constraint ck_trial_intent_resolved check ((status = 'PENDING') = (resolved_at is null)
        and (status = 'PENDING') = (resolution is null) and (status = 'PENDING') = (subscription_id is null)),
    constraint ck_trial_intent_resolution check (resolution is null
        or (status = 'STARTED' and resolution in ('AFTER_COMMIT', 'RETRY', 'MANUAL', 'EXISTING_TRIAL'))
        or (status = 'NOT_STARTED' and resolution = 'SUBSCRIPTION_EXISTS')),
    constraint ck_trial_intent_attempts check (failed_attempts >= 0)
);

-- the daily retry reads pending intents least recently attempted first (never attempted first), so a failing intent
-- moves behind the others and can't starve them
create index ix_trial_intent_retry on trial_intent (last_attempt_at nulls first, created_at) where status = 'PENDING';

create function trial_intent_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'trial intents can''t be deleted';
    end if;
    if tg_op = 'INSERT' then
        if new.status <> 'PENDING' or new.failed_attempts <> 0 then
            raise exception 'new trial intents must be pending with no attempts';
        end if;
        return new;
    end if;
    if old.status <> 'PENDING' then
        raise exception 'resolved trial intents can''t be changed';
    end if;
    if new.business_id is distinct from old.business_id or new.config_version is distinct from old.config_version
            or new.created_at is distinct from old.created_at or new.failed_attempts < old.failed_attempts then
        raise exception 'restaurant, configuration version and creation time can''t be changed; attempts only grow';
    end if;
    return new;
end
$$;

create trigger trial_intent_guard before insert or update or delete on trial_intent
    for each row execute function trial_intent_guard();
