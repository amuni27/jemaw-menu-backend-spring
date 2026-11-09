-- AGF SUB-R4-02 (V23.2, PL-1): the financial ledger, payment evidence and the PROVIDER guard (BD-16, BD-24, F1, F2).
-- Additive: no existing row changes and nothing is seeded. AD-19: ledger rows are never deleted, by any path.

lock table business_subscription in access exclusive mode;

do $$
begin
    if exists (select 1 from business_subscription where source = 'PROVIDER') then
        raise exception 'V23.2 refused: PROVIDER subscriptions exist without verified payment evidence';
    end if;
end $$;

-- checkout_session_id is optional: an automatic Stripe renewal has an invoice but no checkout (TA §6).
create table billing_invoice (
    id                   varchar(36)  not null,
    business_id          varchar(255) not null,
    checkout_session_id  varchar(36),
    price_id             varchar(36)  not null,
    period_start         timestamp(6) with time zone not null,
    period_end           timestamp(6) with time zone not null,
    amount_minor         bigint       not null,
    currency             varchar(3)   not null,
    status               varchar(16)  not null,
    provider_invoice_ref varchar(255),
    constraint pk_billing_invoice primary key (id),
    constraint fk_billing_invoice_business foreign key (business_id) references business (id),
    constraint fk_billing_invoice_checkout foreign key (checkout_session_id) references checkout_session (id),
    constraint fk_billing_invoice_price foreign key (price_id) references plan_price (id),
    constraint ck_billing_invoice_status check (status in ('OPEN', 'PAID', 'VOID', 'UNCOLLECTIBLE', 'REFUNDED')),
    constraint ck_billing_invoice_amount check (amount_minor > 0),
    constraint ck_billing_invoice_currency check (currency in ('USD', 'ETB')),
    constraint ck_billing_invoice_dates check (period_end > period_start)
);

create unique index uk_billing_invoice_provider_ref on billing_invoice (provider_invoice_ref) where provider_invoice_ref is not null;

create table payment (
    id                   varchar(36)  not null,
    business_id          varchar(255) not null,
    invoice_id           varchar(36),
    checkout_session_id  varchar(36),
    provider             varchar(32)  not null,
    provider_payment_ref varchar(255) not null,
    amount_minor         bigint       not null,
    currency             varchar(3)   not null,
    kind                 varchar(16)  not null,
    status               varchar(16)  not null,
    occurred_at          timestamp(6) with time zone not null,
    verified_at          timestamp(6) with time zone,
    verification         varchar(32),
    constraint pk_payment primary key (id),
    constraint uk_payment_provider_ref_kind unique (provider, provider_payment_ref, kind),
    constraint fk_payment_business foreign key (business_id) references business (id),
    constraint fk_payment_invoice foreign key (invoice_id) references billing_invoice (id),
    constraint fk_payment_checkout foreign key (checkout_session_id) references checkout_session (id),
    constraint ck_payment_kind check (kind in ('CHARGE', 'REFUND', 'CHARGEBACK')),
    constraint ck_payment_status check (status in ('PENDING', 'SUCCEEDED', 'FAILED')),
    constraint ck_payment_amount check (amount_minor > 0),
    constraint ck_payment_currency check (currency in ('USD', 'ETB'))
);

create table subscription_period (
    id              varchar(36)  not null,
    subscription_id varchar(36)  not null,
    business_id     varchar(255) not null,
    period_start    timestamp(6) with time zone not null,
    period_end      timestamp(6) with time zone not null,
    invoice_id      varchar(36)  not null,
    payment_id      varchar(36)  not null,
    constraint pk_subscription_period primary key (id),
    constraint uk_subscription_period_payment unique (payment_id),
    constraint uk_subscription_period_invoice unique (invoice_id),
    constraint uk_subscription_period_start unique (subscription_id, period_start),
    constraint fk_subscription_period_subscription foreign key (subscription_id) references business_subscription (id),
    constraint fk_subscription_period_business foreign key (business_id) references business (id),
    constraint fk_subscription_period_invoice foreign key (invoice_id) references billing_invoice (id),
    constraint fk_subscription_period_payment foreign key (payment_id) references payment (id),
    constraint ck_subscription_period_dates check (period_end > period_start)
);

create function payment_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'payments can''t be changed or removed';
end $$;

create trigger payment_append_only before update or delete on payment
    for each row execute function payment_append_only();

create function billing_invoice_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then raise exception 'invoices can''t be removed'; end if;
    if to_jsonb(new) - 'status' is distinct from to_jsonb(old) - 'status' then
        raise exception 'only an invoice''s status can change';
    end if;
    return new;
end $$;

create trigger billing_invoice_guard before update or delete on billing_invoice
    for each row execute function billing_invoice_guard();

create function subscription_period_guard() returns trigger language plpgsql as $$
declare
    pay  payment%rowtype;
    inv  billing_invoice%rowtype;
    sub  business_subscription%rowtype;
    co   checkout_session%rowtype;
    plan varchar;
begin
    if tg_op <> 'INSERT' then raise exception 'subscription periods can''t be changed or removed'; end if;

    select * into pay from payment where id = new.payment_id;
    -- FOR SHARE: the invoice can't stop being PAID while this period consumes it.
    select * into inv from billing_invoice where id = new.invoice_id for share;
    select * into sub from business_subscription where id = new.subscription_id;
    -- Missing evidence is refused by the foreign keys.
    if pay.id is null or inv.id is null or sub.id is null then return new; end if;

    if sub.source <> 'PROVIDER' then raise exception 'subscription periods belong to PROVIDER subscriptions'; end if;
    if pay.kind <> 'CHARGE' or pay.status <> 'SUCCEEDED' or pay.verified_at is null
            or pay.verification is null or pay.verification not in ('SIGNED_WEBHOOK+API_FETCH', 'RECONCILIATION_API_FETCH') then
        raise exception 'the payment is not verified evidence';
    end if;
    if inv.status <> 'PAID' then raise exception 'the invoice is not paid'; end if;
    if pay.invoice_id is distinct from new.invoice_id then raise exception 'the payment doesn''t belong to this invoice'; end if;
    if new.business_id <> sub.business_id or new.business_id <> pay.business_id or new.business_id <> inv.business_id then
        raise exception 'the period, subscription, payment and invoice must belong to one restaurant';
    end if;
    if pay.checkout_session_id is not null and inv.checkout_session_id is not null and pay.checkout_session_id <> inv.checkout_session_id then
        raise exception 'the payment and invoice belong to different checkouts';
    end if;
    select * into co from checkout_session where id = coalesce(inv.checkout_session_id, pay.checkout_session_id);
    if co.id is not null and co.business_id <> new.business_id then
        raise exception 'the period and its checkout must belong to one restaurant';
    end if;
    if pay.amount_minor <> inv.amount_minor or pay.currency <> inv.currency then
        raise exception 'the payment and invoice amount and currency must be equal';
    end if;
    if new.period_start <> inv.period_start or new.period_end <> inv.period_end then
        raise exception 'the period doesn''t match the invoice';
    end if;
    select plan_code into plan from plan_price where id = inv.price_id;
    if plan is distinct from sub.plan_code then raise exception 'the invoice plan doesn''t match the subscription'; end if;
    if pay.provider is distinct from sub.provider or (co.id is not null and co.provider <> pay.provider) then
        raise exception 'the provider doesn''t match';
    end if;
    return new;
end $$;

create trigger subscription_period_guard before insert or update or delete on subscription_period
    for each row execute function subscription_period_guard();

-- Replaces the V20 function (the V13 trigger keeps calling it). MANUAL, TRIAL and OWNER behave exactly as before (R-4).
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
    if new.source = 'PROVIDER' then
        if new.price_id is distinct from old.price_id or new.provider is distinct from old.provider
                or new.provider_subscription_ref is distinct from old.provider_subscription_ref then
            raise exception 'a PROVIDER row''s price, provider and provider reference can''t be changed';
        end if;
        if new.period_end is null or new.period_end < old.period_end then
            raise exception 'a PROVIDER period can only move forward';
        end if;
    end if;
    return new;
end $$;

drop trigger business_subscription_refuse_provider on business_subscription;

-- Reads the row as it stands at commit, so only the transaction's final period_end needs evidence.
create function provider_row_has_evidence() returns trigger language plpgsql as $$
begin
    if not exists (select 1 from business_subscription s join subscription_period p on p.subscription_id = s.id and p.period_end = s.period_end where s.id = new.id) then
        raise exception 'a PROVIDER subscription without a verified paid period can''t be committed';
    end if;
    return null;
end $$;

create constraint trigger provider_row_has_evidence after insert or update on business_subscription
    deferrable initially deferred
    for each row when (new.source = 'PROVIDER') execute function provider_row_has_evidence();
