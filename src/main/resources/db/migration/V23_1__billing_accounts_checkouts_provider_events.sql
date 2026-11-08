-- AGF SUB-R4-01 (V23.1, PL-1): billing accounts, checkout sessions and the provider event inbox (BD-7, BD-14).
-- Schema only. Additive: no existing row changes and nothing is seeded.

create table billing_account (
    id                    varchar(36)  not null,
    business_id           varchar(255) not null,
    market                varchar(2)   not null,
    provider              varchar(32)  not null,
    provider_customer_ref varchar(255),
    billing_email         varchar(255),
    constraint pk_billing_account primary key (id),
    constraint uk_billing_account_business_provider unique (business_id, provider),
    constraint fk_billing_account_business foreign key (business_id) references business (id),
    constraint ck_billing_account_market check (market in ('US', 'ET'))
);

create function billing_account_guard() returns trigger language plpgsql as $$
declare
    restaurant_country varchar(2);
begin
    if tg_op = 'UPDATE' then
        if new.market is distinct from old.market or new.business_id is distinct from old.business_id then
            raise exception 'a billing account''s market and restaurant can''t be changed';
        end if;
        return new;
    end if;
    select country into restaurant_country from business where id = new.business_id;
    if restaurant_country is not null and new.market is distinct from restaurant_country and new.market in ('US', 'ET') then
        raise exception 'a billing account market must be the restaurant''s country';
    end if;
    return new;
end $$;

create trigger billing_account_guard before insert or update on billing_account
    for each row execute function billing_account_guard();

create table checkout_session (
    id                       varchar(36)  not null,
    business_id              varchar(255) not null,
    owner_user_id            varchar(255) not null,
    price_id                 varchar(36)  not null,
    config_version           integer      not null,
    kind                     varchar(32)  not null,
    replaces_subscription_id varchar(36),
    provider                 varchar(32)  not null,
    provider_session_ref     varchar(255),
    provider_session_url     varchar(2048),
    status                   varchar(16)  not null,
    cancel_requested_at      timestamp(6) with time zone,
    cancelled_reason         varchar(64),
    idempotency_key          varchar(255) not null,
    request_hash             varchar(64)  not null,
    expires_at               timestamp(6) with time zone not null,
    expected_amount_minor    bigint       not null,
    expected_currency        varchar(3)   not null,
    created_at               timestamp(6) with time zone not null,
    row_version              integer      not null default 0,
    constraint pk_checkout_session primary key (id),
    constraint uk_checkout_session_idempotency unique (business_id, idempotency_key),
    constraint fk_checkout_session_business foreign key (business_id) references business (id),
    constraint fk_checkout_session_owner foreign key (owner_user_id) references users (id),
    constraint fk_checkout_session_price foreign key (price_id) references plan_price (id),
    constraint fk_checkout_session_config foreign key (config_version) references monetization_config (version),
    constraint fk_checkout_session_replaces foreign key (replaces_subscription_id) references business_subscription (id),
    constraint ck_checkout_session_kind check (kind in ('NEW', 'CONVERSION', 'UPGRADE', 'RENEWAL', 'DOWNGRADE_RENEWAL')),
    constraint ck_checkout_session_status check (status in ('CREATING', 'OPEN', 'CANCEL_REQUESTED', 'COMPLETED', 'EXPIRED', 'CANCELLED', 'CREATE_FAILED', 'FAILED')),
    constraint ck_checkout_session_open_ref check (status <> 'OPEN' or provider_session_ref is not null),
    constraint ck_checkout_session_cancel check ((cancel_requested_at is not null) = (status in ('CANCEL_REQUESTED', 'CANCELLED')) or status = 'CREATE_FAILED'),
    constraint ck_checkout_session_expiry check (expires_at <= created_at + interval '24 hours'),
    constraint ck_checkout_session_amount check (expected_amount_minor > 0),
    constraint ck_checkout_session_currency check (expected_currency in ('USD', 'ETB'))
);

create unique index uk_checkout_session_active on checkout_session (business_id) where status in ('CREATING', 'OPEN');
create unique index uk_checkout_session_provider_ref on checkout_session (provider, provider_session_ref) where provider_session_ref is not null;

create table provider_event (
    id           varchar(36)  not null,
    provider     varchar(32)  not null,
    event_id     varchar(255) not null,
    type         varchar(128) not null,
    payload      jsonb        not null,
    received_at  timestamp(6) with time zone not null default now(),
    processed_at timestamp(6) with time zone,
    attempts     integer      not null default 0,
    last_error   text,
    status       varchar(16)  not null default 'PENDING',
    constraint pk_provider_event primary key (id),
    constraint uk_provider_event unique (provider, event_id),
    constraint ck_provider_event_status check (status in ('PENDING', 'PROCESSED', 'IGNORED', 'FAILED', 'QUARANTINED')),
    constraint ck_provider_event_attempts check (attempts >= 0)
);
