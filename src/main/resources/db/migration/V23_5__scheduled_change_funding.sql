-- AGF SUB-R4-15 (V23.5; PO D3, P1, P2): a paid downgrade is funded by exactly one verified charge, through a checkout made for it.
-- Additive: no existing row changes; V1–V23.4 are untouched. A funding association, once recorded, never changes.

lock table checkout_session, scheduled_change in access exclusive mode;

do $$
begin
    if exists (select 1 from checkout_session where kind = 'DOWNGRADE_RENEWAL') then
        raise exception 'V23.5 refused: downgrade-renewal checkouts exist without a scheduled change';
    end if;
end $$;

-- P2: every downgrade-renewal checkout names its exact change, and no other kind names one.
alter table checkout_session add column scheduled_change_id varchar(36);
alter table checkout_session add constraint fk_checkout_session_change foreign key (scheduled_change_id) references scheduled_change (id);
alter table checkout_session add constraint ck_checkout_session_change check ((kind = 'DOWNGRADE_RENEWAL') = (scheduled_change_id is not null));
-- P2: one checkout per change while it can still receive payment (CANCEL_REQUESTED included).
create unique index uk_checkout_session_change_payable on checkout_session (scheduled_change_id)
    where scheduled_change_id is not null and status in ('CREATING', 'OPEN', 'CANCEL_REQUESTED');

alter table scheduled_change add column created_at timestamp(6) with time zone not null default now();
alter table scheduled_change add column funding_payment_id varchar(36);
alter table scheduled_change add column funding_invoice_id varchar(36);
alter table scheduled_change add column funded_at timestamp(6) with time zone;
alter table scheduled_change add column applied_subscription_id varchar(36);
alter table scheduled_change add constraint fk_scheduled_change_funding_payment foreign key (funding_payment_id) references payment (id);
alter table scheduled_change add constraint fk_scheduled_change_funding_invoice foreign key (funding_invoice_id) references billing_invoice (id);
alter table scheduled_change add constraint fk_scheduled_change_applied foreign key (applied_subscription_id) references business_subscription (id);
-- C3: all of the funding or none of it.
alter table scheduled_change add constraint ck_scheduled_change_funding_complete check (
    (funding_payment_id is null) = (funding_invoice_id is null) and (funding_payment_id is null) = (funded_at is null));
-- C5: only a paid downgrade can be funded.
alter table scheduled_change add constraint ck_scheduled_change_funding_paid check (
    funding_payment_id is null or (kind = 'DOWNGRADE' and target_price_id is not null));
-- C11: a paid change is APPLIED only with its funding and the exact resulting subscription; only APPLIED names one.
alter table scheduled_change add constraint ck_scheduled_change_applied check (
    (applied_subscription_id is null or status = 'APPLIED')
    and (status <> 'APPLIED' or target_price_id is null or (funding_payment_id is not null and applied_subscription_id is not null)));
-- C4: a payment, an invoice and a resulting subscription each fund or result from at most one change.
create unique index uk_scheduled_change_funding_payment on scheduled_change (funding_payment_id) where funding_payment_id is not null;
create unique index uk_scheduled_change_funding_invoice on scheduled_change (funding_invoice_id) where funding_invoice_id is not null;
create unique index uk_scheduled_change_applied on scheduled_change (applied_subscription_id) where applied_subscription_id is not null;

-- P2: the checkout's change belongs to the same restaurant, replaces the same row and targets the same price.
create function checkout_session_change_guard() returns trigger language plpgsql as $$
declare
    chg scheduled_change%rowtype;
begin
    if tg_op = 'UPDATE' and new.scheduled_change_id is distinct from old.scheduled_change_id then
        raise exception 'a checkout''s scheduled change can''t be changed';
    end if;
    if new.scheduled_change_id is null or tg_op = 'UPDATE' then return new; end if;
    select * into chg from scheduled_change where id = new.scheduled_change_id;
    if chg.id is null then return new; end if; -- the foreign key refuses it
    if chg.business_id <> new.business_id or chg.from_subscription_id is distinct from new.replaces_subscription_id
            or chg.target_price_id is distinct from new.price_id or chg.kind <> 'DOWNGRADE' then
        raise exception 'a downgrade-renewal checkout must match its scheduled change';
    end if;
    if chg.status not in ('PENDING', 'AWAITING_PAYMENT') or chg.funding_payment_id is not null then
        raise exception 'a downgrade-renewal checkout needs an open, unfunded scheduled change';
    end if;
    return new;
end $$;

create trigger checkout_session_change_guard before insert or update on checkout_session
    for each row execute function checkout_session_change_guard();

-- C6–C9: funding is set once, only while the change is open, and only by the exact verified charge and PAID invoice.
create function scheduled_change_funding_guard() returns trigger language plpgsql as $$
declare
    pay  payment%rowtype;
    inv  billing_invoice%rowtype;
    co   checkout_session%rowtype;
    sub  business_subscription%rowtype;
    pr   plan_price%rowtype;
begin
    if tg_op = 'DELETE' then
        if old.funding_payment_id is not null then raise exception 'a funded scheduled change can''t be removed'; end if;
        return old;
    end if;
    if tg_op = 'INSERT' then
        if new.funding_payment_id is not null or new.funding_invoice_id is not null or new.funded_at is not null or new.applied_subscription_id is not null then
            raise exception 'a scheduled change starts unfunded and unapplied';
        end if;
        return new;
    end if;
    if new.created_at is distinct from old.created_at then raise exception 'a scheduled change''s creation time can''t be changed'; end if;
    if old.applied_subscription_id is not null and new.applied_subscription_id is distinct from old.applied_subscription_id then
        raise exception 'a scheduled change''s resulting subscription can''t be changed';
    end if;
    if old.funding_payment_id is not null then
        if new.funding_payment_id is distinct from old.funding_payment_id or new.funding_invoice_id is distinct from old.funding_invoice_id
                or new.funded_at is distinct from old.funded_at then
            raise exception 'a funding association can''t be changed or removed';
        end if;
        if new.business_id is distinct from old.business_id or new.from_subscription_id is distinct from old.from_subscription_id or new.kind is distinct from old.kind
                or new.target_plan_code is distinct from old.target_plan_code or new.target_price_id is distinct from old.target_price_id
                or new.effective_at is distinct from old.effective_at then
            raise exception 'a funded scheduled change can''t be retargeted';
        end if;
        return new;
    end if;
    if new.funding_payment_id is null then return new; end if;

    -- funding is being recorded now
    if old.status not in ('PENDING', 'AWAITING_PAYMENT') then raise exception 'only an open scheduled change can be funded'; end if;
    select * into pay from payment where id = new.funding_payment_id;
    select * into inv from billing_invoice where id = new.funding_invoice_id for share;
    select * into sub from business_subscription where id = new.from_subscription_id;
    if pay.id is null or inv.id is null or sub.id is null then return new; end if; -- the foreign keys refuse it
    if pay.kind <> 'CHARGE' or pay.status <> 'SUCCEEDED' or pay.verified_at is null or pay.verification is null then
        raise exception 'the funding payment is not a verified charge';
    end if;
    if pay.business_id <> new.business_id or inv.business_id <> new.business_id then raise exception 'the funding belongs to another restaurant'; end if;
    if pay.provider is distinct from sub.provider then raise exception 'the funding provider doesn''t match the subscription'; end if;
    if pay.invoice_id is distinct from new.funding_invoice_id then raise exception 'the funding payment doesn''t belong to the funding invoice'; end if;
    select * into co from checkout_session where id = pay.checkout_session_id;
    if co.id is null or co.scheduled_change_id is distinct from new.id or inv.checkout_session_id is distinct from co.id then
        raise exception 'the funding must come through a checkout made for this scheduled change';
    end if;
    if co.business_id <> new.business_id or co.provider <> pay.provider or co.replaces_subscription_id is distinct from new.from_subscription_id
            or co.price_id is distinct from new.target_price_id then
        raise exception 'the funding checkout doesn''t match the scheduled change';
    end if;
    if inv.status <> 'PAID' or inv.price_id is distinct from new.target_price_id then raise exception 'the funding invoice is not a paid invoice for the target price'; end if;
    select * into pr from plan_price where id = new.target_price_id;
    if inv.amount_minor <> pr.amount_minor or inv.currency <> pr.currency or pay.amount_minor <> inv.amount_minor or pay.currency <> inv.currency then
        raise exception 'the funding amount and currency must equal the target price';
    end if;
    if inv.period_start <> new.effective_at then raise exception 'the funding invoice must start at the scheduled change''s boundary'; end if;
    return new;
end $$;

create trigger scheduled_change_funding_guard before insert or update or delete on scheduled_change
    for each row execute function scheduled_change_funding_guard();

-- C10 (P1): at commit, an APPLIED paid change names the exact resulting subscription, price, invoice, payment and boundary.
create function scheduled_change_applied_evidence() returns trigger language plpgsql as $$
begin
    if not exists (
            select 1 from scheduled_change c
            join business_subscription t on t.id = c.applied_subscription_id
            join subscription_period sp on sp.subscription_id = t.id and sp.payment_id = c.funding_payment_id and sp.invoice_id = c.funding_invoice_id
            join business_subscription f on f.id = c.from_subscription_id
            where c.id = new.id and t.business_id = c.business_id and t.source = 'PROVIDER' and t.plan_code = c.target_plan_code
              and t.price_id = c.target_price_id and sp.period_start = c.effective_at
              and f.status = 'EXPIRED' and f.replaced_by_id = t.id) then
        raise exception 'an applied paid scheduled change needs its exact funded period on its resulting subscription';
    end if;
    return null;
end $$;

create constraint trigger scheduled_change_applied_evidence after update on scheduled_change
    deferrable initially deferred
    for each row when (new.status = 'APPLIED' and new.target_price_id is not null) execute function scheduled_change_applied_evidence();
