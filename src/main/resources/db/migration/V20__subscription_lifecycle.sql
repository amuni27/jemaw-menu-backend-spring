-- AGF SUB-R2-06: lifecycle columns, PAST_DUE, the replaced subscription guard, and scheduled changes (BD-11, BD-24, R-4).
-- Additive: no existing row changes. PROVIDER rows stay refused until the payment-evidence migration exists.

lock table business_subscription in access exclusive mode;

do $$
begin
    if exists (select 1 from business_subscription where source = 'PROVIDER') then
        raise exception 'V20 refused: PROVIDER subscriptions exist without verified payment evidence';
    end if;
end $$;

alter table business_subscription
    add column price_id                  varchar(36),
    add column current_period_start      timestamp(6) with time zone,
    add column grace_days                integer,
    add column past_due_since            timestamp(6) with time zone,
    add column open_ended                boolean not null default false,
    add column provider                  varchar(32),
    add column provider_subscription_ref varchar(255),
    add column ended_reason              varchar(32),
    add column replaced_by_id            varchar(36);

create unique index uk_business_subscription_provider_ref on business_subscription (provider_subscription_ref)
    where provider_subscription_ref is not null;

alter table business_subscription drop constraint ck_business_subscription_status;
alter table business_subscription add constraint ck_business_subscription_status
    check (status in ('TRIAL', 'ACTIVE', 'PAST_DUE', 'GRANDFATHERED', 'EXPIRED'));
alter table business_subscription add constraint ck_business_subscription_past_due
    check ((past_due_since is not null) = (status = 'PAST_DUE'));

-- The trigger created by V13 keeps calling this function. Period end stays editable here: R-4 for PROVIDER rows is
-- enforced by the refusal trigger below until verified payments exist.
create or replace function business_subscription_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then raise exception 'subscriptions can''t be deleted'; end if;
    if old.status = 'EXPIRED' then raise exception 'ended subscriptions can''t be changed'; end if;
    if new.id is distinct from old.id or new.business_id is distinct from old.business_id
            or new.plan_code is distinct from old.plan_code or new.config_version is distinct from old.config_version
            or new.source is distinct from old.source or new.created_at is distinct from old.created_at
            or new.created_by_user_id is distinct from old.created_by_user_id then
        raise exception 'plan, configuration version, restaurant and source can''t be changed';
    end if;
    if (old.price_id is not null and new.price_id is distinct from old.price_id)
            or (old.provider is not null and new.provider is distinct from old.provider)
            or (old.provider_subscription_ref is not null and new.provider_subscription_ref is distinct from old.provider_subscription_ref)
            or (old.grace_days is not null and new.grace_days is distinct from old.grace_days) then
        raise exception 'price, provider, provider reference and grace days can''t be changed once set';
    end if;
    if new.status is distinct from old.status and not (
            new.status = 'EXPIRED'
            or (old.status = 'ACTIVE' and new.status = 'PAST_DUE')
            or (old.status = 'PAST_DUE' and new.status = 'ACTIVE')) then
        raise exception 'a current subscription can only be ended';
    end if;
    return new;
end $$;

-- Named to fire after business_subscription_guard (same-event triggers run alphabetically).
create function business_subscription_refuse_provider() returns trigger language plpgsql as $$
begin
    if new.source = 'PROVIDER' then
        if tg_op = 'INSERT' then raise exception 'PROVIDER subscriptions require verified payment evidence'; end if;
        if old.source is distinct from new.source then raise exception 'PROVIDER subscriptions require verified payment evidence'; end if;
    end if;
    return new;
end $$;

create trigger business_subscription_refuse_provider before insert or update on business_subscription
    for each row execute function business_subscription_refuse_provider();

create table scheduled_change (
    id                    varchar(36)  not null,
    business_id           varchar(255) not null,
    from_subscription_id  varchar(36)  not null,
    kind                  varchar(16)  not null,
    target_plan_code      varchar(16),
    target_price_id       varchar(36),
    effective_at          timestamp(6) with time zone not null,
    status                varchar(16)  not null,
    provider_schedule_ref varchar(255),
    constraint pk_scheduled_change primary key (id),
    constraint fk_scheduled_change_business foreign key (business_id) references business (id),
    constraint fk_scheduled_change_subscription foreign key (from_subscription_id) references business_subscription (id),
    constraint fk_scheduled_change_plan foreign key (target_plan_code) references subscription_plan (code),
    constraint ck_scheduled_change_kind check (kind in ('DOWNGRADE', 'CANCEL')),
    constraint ck_scheduled_change_status check (status in ('PENDING', 'AWAITING_PAYMENT', 'APPLIED', 'CANCELLED', 'SUPERSEDED', 'FAILED'))
);

create unique index uk_scheduled_change_pending on scheduled_change (business_id) where status in ('PENDING', 'AWAITING_PAYMENT');
