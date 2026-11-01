-- AGF E4-M3: plans (one per monetization model, seeded, read-only) and restaurant subscriptions. No price, trial length
-- or limit data (E4-Q2/Q3/Q8 open). No restaurant gets a subscription here (E4-Q10). At most one subscription per
-- restaurant is current; ended (EXPIRED) ones are kept as frozen history with their own plan and configuration version
-- (D-M3-11). Plan, configuration version, restaurant and source never change (D-M3-10). V1-V12 untouched.
create table subscription_plan (
    code      varchar(16) not null,
    model     varchar(16) not null,
    setting   varchar(16) not null,
    ad_source varchar(16) not null,
    paid      boolean     not null,
    constraint pk_subscription_plan primary key (code),
    constraint ck_subscription_plan_model check (model in ('MODEL_1', 'MODEL_2', 'MODEL_3', 'MODEL_4') and code = model),
    constraint ck_subscription_plan_setting check ((model = 'MODEL_1') = (setting = 'SETTING_1')
        and setting in ('SETTING_1', 'SETTING_2')),
    constraint ck_subscription_plan_ad_source check (ad_source in ('NONE', 'AGAFARI', 'RESTAURANT'))
);

insert into subscription_plan (code, model, setting, ad_source, paid) values
    ('MODEL_1', 'MODEL_1', 'SETTING_1', 'NONE', true),        -- free trial, then paid (the trial is a status)
    ('MODEL_2', 'MODEL_2', 'SETTING_2', 'AGAFARI', false),    -- free, Agafari ads
    ('MODEL_3', 'MODEL_3', 'SETTING_2', 'RESTAURANT', true),  -- paid, the restaurant's own ads
    ('MODEL_4', 'MODEL_4', 'SETTING_2', 'NONE', true);        -- lower-cost paid, no ads

create function subscription_plan_read_only() returns trigger language plpgsql as $$
begin
    raise exception 'subscription_plan is read-only';
end
$$;

create trigger subscription_plan_no_change before update or delete on subscription_plan
    for each row execute function subscription_plan_read_only();

create table business_subscription (
    id                 varchar(36)  not null,
    business_id        varchar(255) not null,
    plan_code          varchar(16)  not null,
    status             varchar(16)  not null,
    config_version     integer      not null,
    period_end         timestamp(6) with time zone,
    transition_date    timestamp(6) with time zone,
    source             varchar(16)  not null,
    created_at         timestamp(6) with time zone not null,
    created_by_user_id varchar(255) not null,
    updated_at         timestamp(6) with time zone not null,
    updated_by_user_id varchar(255) not null,
    ended_at           timestamp(6) with time zone,
    row_version        integer      not null,
    constraint pk_business_subscription primary key (id),
    constraint fk_business_subscription_business foreign key (business_id) references business (id),
    constraint fk_business_subscription_plan foreign key (plan_code) references subscription_plan (code),
    constraint fk_business_subscription_config foreign key (config_version) references monetization_config (version),
    constraint ck_business_subscription_status check (status in ('TRIAL', 'ACTIVE', 'GRANDFATHERED', 'EXPIRED')),
    constraint ck_business_subscription_source check (source in ('MANUAL', 'PROVIDER')),
    constraint ck_business_subscription_period check (period_end is null or period_end > created_at),
    constraint ck_business_subscription_ended check ((status = 'EXPIRED') = (ended_at is not null)),
    constraint ck_business_subscription_trial check (status <> 'TRIAL' or plan_code = 'MODEL_1')
);

-- one current subscription per restaurant; ended ones are history
create unique index uk_business_subscription_current on business_subscription (business_id) where status <> 'EXPIRED';
create index ix_business_subscription_business on business_subscription (business_id, created_at desc);

create function business_subscription_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'subscriptions can''t be deleted';
    end if;
    if old.status = 'EXPIRED' then
        raise exception 'ended subscriptions can''t be changed';
    end if;
    if new.id is distinct from old.id or new.business_id is distinct from old.business_id
            or new.plan_code is distinct from old.plan_code or new.config_version is distinct from old.config_version
            or new.source is distinct from old.source or new.created_at is distinct from old.created_at
            or new.created_by_user_id is distinct from old.created_by_user_id then
        raise exception 'plan, configuration version, restaurant and source can''t be changed';
    end if;
    if new.status is distinct from old.status and new.status <> 'EXPIRED' then
        raise exception 'a current subscription can only be ended';
    end if;
    return new;
end
$$;

create trigger business_subscription_guard before update or delete on business_subscription
    for each row execute function business_subscription_guard();
