-- AGF SUB-R3-01: versioned, append-only prices per plan, market and interval (BD-8, BD-24). Seeds no price: amounts are a
-- business decision (AD-10). Additive: no existing row changes.

create table plan_price (
    id                 varchar(36)  not null,
    plan_code          varchar(16)  not null,
    market             varchar(2)   not null,
    currency           varchar(3)   not null,
    billing_interval   varchar(8)   not null,
    amount_minor       bigint       not null,
    version            integer      not null,
    provider_price_ref varchar(255),
    active             boolean      not null default false,
    valid_from         timestamp(6) with time zone not null,
    constraint pk_plan_price primary key (id),
    constraint uk_plan_price_version unique (plan_code, market, billing_interval, version),
    constraint fk_plan_price_plan foreign key (plan_code) references subscription_plan (code),
    constraint ck_plan_price_market_currency check ((market = 'US' and currency = 'USD') or (market = 'ET' and currency = 'ETB')),
    constraint ck_plan_price_interval check (billing_interval in ('MONTH', 'YEAR')),
    constraint ck_plan_price_amount check (amount_minor > 0),
    constraint ck_plan_price_version check (version > 0)
);

create function plan_price_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'prices can''t be changed; add a new version';
end $$;

create trigger plan_price_append_only before update or delete on plan_price
    for each row execute function plan_price_append_only();

alter table business_subscription add constraint fk_business_subscription_price foreign key (price_id) references plan_price (id);
