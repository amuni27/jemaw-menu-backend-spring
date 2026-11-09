-- AGF SUB-R4-03 (V23.3, PL-1): financial exceptions, refund requests and the market calendar (BD-22, BD-23, R-3, R-6, I4).
-- Schema only. Additive: no existing row changes and nothing is seeded.

create table refund_request (
    id                  varchar(36)  not null,
    payment_id          varchar(36)  not null,
    amount_minor        bigint       not null,
    reason              varchar(64)  not null,
    request_key         varchar(255) not null,
    status              varchar(16)  not null,
    idempotency_key     varchar(255),
    submitted_at        timestamp(6) with time zone,
    provider_refund_ref varchar(255),
    constraint pk_refund_request primary key (id),
    constraint uk_refund_request_payment_key unique (payment_id, request_key),
    constraint fk_refund_request_payment foreign key (payment_id) references payment (id),
    constraint ck_refund_request_status check (status in ('REQUESTED', 'SUBMITTING', 'SUBMITTED', 'SUCCEEDED', 'FAILED', 'FAILED_DEFINITE', 'UNKNOWN')),
    constraint ck_refund_request_amount check (amount_minor > 0),
    constraint ck_refund_request_submission check (status = 'REQUESTED' or (idempotency_key is not null and submitted_at is not null)),
    constraint ck_refund_request_provider_ref check (status not in ('SUBMITTED', 'SUCCEEDED') or provider_refund_ref is not null)
);

create unique index uk_refund_request_provider_ref on refund_request (provider_refund_ref) where provider_refund_ref is not null;

create function refund_request_guard() returns trigger language plpgsql as $$
declare
    pay payment%rowtype;
begin
    if tg_op = 'DELETE' then raise exception 'refund requests can''t be removed'; end if;
    if tg_op = 'INSERT' then
        if new.status is distinct from 'REQUESTED' then raise exception 'a refund request starts as REQUESTED'; end if;
        select * into pay from payment where id = new.payment_id;
        -- A missing payment is refused by the foreign key.
        if pay.id is not null and (pay.kind <> 'CHARGE' or pay.status <> 'SUCCEEDED' or pay.verified_at is null or pay.verification is null) then
            raise exception 'a refund needs a verified charge';
        end if;
        return new;
    end if;
    if new.id is distinct from old.id or new.payment_id is distinct from old.payment_id or new.amount_minor is distinct from old.amount_minor
            or new.reason is distinct from old.reason or new.request_key is distinct from old.request_key then
        raise exception 'a refund request''s payment, amount, reason and key can''t be changed';
    end if;
    -- BD-22: a retry keeps the same key, and the provider's window counts from the first submission.
    if (old.idempotency_key is not null and new.idempotency_key is distinct from old.idempotency_key)
            or (old.submitted_at is not null and new.submitted_at is distinct from old.submitted_at)
            or (old.provider_refund_ref is not null and new.provider_refund_ref is distinct from old.provider_refund_ref) then
        raise exception 'a refund request''s submission details can''t be changed once set';
    end if;
    if new.status is distinct from old.status and not (
            (old.status = 'REQUESTED' and new.status = 'SUBMITTING')
            or (old.status = 'SUBMITTING' and new.status in ('SUBMITTED', 'FAILED_DEFINITE', 'UNKNOWN'))
            or (old.status = 'SUBMITTED' and new.status in ('SUCCEEDED', 'FAILED'))
            or (old.status = 'UNKNOWN' and new.status in ('SUBMITTING', 'SUBMITTED', 'SUCCEEDED'))) then
        raise exception 'a refund request can''t move from % to %', old.status, new.status;
    end if;
    return new;
end $$;

create trigger refund_request_guard before insert or update or delete on refund_request
    for each row execute function refund_request_guard();

-- code has no fixed list: the approved set is still growing (PO, SUB-R4-03).
-- due_at is optional until the SLA calendar story sets it.
create table billing_exception (
    id                      varchar(36)  not null,
    business_id             varchar(255) not null,
    code                    varchar(64)  not null,
    payment_id              varchar(36),
    checkout_id             varchar(36),
    provider_event_id       varchar(36),
    refund_request_id       varchar(36),
    amount_minor            bigint,
    currency                varchar(3),
    paid_at                 timestamp(6) with time zone,
    involves_customer_funds boolean      not null,
    facts                   jsonb        not null,
    status                  varchar(16)  not null,
    owner_user_id           varchar(255),
    claimed_at              timestamp(6) with time zone,
    due_at                  timestamp(6) with time zone,
    escalated_at            timestamp(6) with time zone,
    escalation_reason       text,
    escalated_to            varchar(255),
    resolution              varchar(32),
    resolution_note         text,
    resolved_by             varchar(255),
    resolved_at             timestamp(6) with time zone,
    row_version             integer      not null default 0,
    created_at              timestamp(6) with time zone not null,
    constraint pk_billing_exception primary key (id),
    constraint fk_billing_exception_business foreign key (business_id) references business (id),
    constraint fk_billing_exception_payment foreign key (payment_id) references payment (id),
    constraint fk_billing_exception_checkout foreign key (checkout_id) references checkout_session (id),
    constraint fk_billing_exception_event foreign key (provider_event_id) references provider_event (id),
    constraint fk_billing_exception_refund foreign key (refund_request_id) references refund_request (id),
    constraint fk_billing_exception_owner foreign key (owner_user_id) references users (id),
    constraint ck_billing_exception_status check (status in ('AUTO_HANDLING', 'OPEN', 'IN_REVIEW', 'ESCALATED', 'RESOLVED')),
    constraint ck_billing_exception_resolution check (resolution in ('REFUNDED_AUTOMATICALLY', 'FUNDS_HANDLED_EXTERNALLY', 'NO_ACTION_REQUIRED')),
    constraint ck_billing_exception_resolved check (
        case when status = 'RESOLVED' then resolution is not null and resolved_by is not null and resolved_at is not null
             else resolution is null and resolved_by is null and resolved_at is null end),
    constraint ck_billing_exception_escalated check (status <> 'ESCALATED' or (escalated_at is not null and escalation_reason is not null)),
    constraint ck_billing_exception_claim check ((owner_user_id is null) = (claimed_at is null)),
    constraint ck_billing_exception_money check ((amount_minor is null) = (currency is null)),
    constraint ck_billing_exception_amount check (amount_minor > 0),
    constraint ck_billing_exception_currency check (currency in ('USD', 'ETB'))
);

create unique index uk_billing_exception_payment on billing_exception (payment_id, code) where payment_id is not null;
create unique index uk_billing_exception_event on billing_exception (provider_event_id, code) where payment_id is null;

create function billing_exception_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then raise exception 'exceptions can''t be removed'; end if;
    if tg_op = 'UPDATE' then
        if old.status = 'RESOLVED' then raise exception 'a resolved exception can''t be changed'; end if;
        -- Everything except the review workflow; a future column is immutable unless listed here.
        if to_jsonb(new) - array['status', 'owner_user_id', 'claimed_at', 'due_at', 'escalated_at', 'escalation_reason', 'escalated_to', 'resolution',
                                 'resolution_note', 'resolved_by', 'resolved_at', 'row_version', 'refund_request_id']
           is distinct from to_jsonb(old) - array['status', 'owner_user_id', 'claimed_at', 'due_at', 'escalated_at', 'escalation_reason', 'escalated_to', 'resolution',
                                 'resolution_note', 'resolved_by', 'resolved_at', 'row_version', 'refund_request_id'] then
            raise exception 'an exception''s financial facts can''t be changed';
        end if;
        if old.refund_request_id is not null and new.refund_request_id is distinct from old.refund_request_id then
            raise exception 'an exception''s refund link can''t be changed';
        end if;
    end if;
    if exists (select 1 from payment p where p.id = new.payment_id and p.business_id <> new.business_id)
            or exists (select 1 from checkout_session c where c.id = new.checkout_id and c.business_id <> new.business_id)
            or exists (select 1 from refund_request r join payment p on p.id = r.payment_id where r.id = new.refund_request_id and p.business_id <> new.business_id) then
        raise exception 'linked records must belong to the exception''s restaurant';
    end if;
    return new;
end $$;

create trigger billing_exception_guard before insert or update or delete on billing_exception
    for each row execute function billing_exception_guard();

create table market_calendar_day (
    market        varchar(2)   not null,
    calendar_date date         not null,
    kind          varchar(16)  not null,
    name          varchar(255) not null,
    source_note   varchar(1024),
    constraint pk_market_calendar_day primary key (market, calendar_date),
    constraint ck_market_calendar_day_market check (market in ('US', 'ET')),
    constraint ck_market_calendar_day_kind check (kind in ('PUBLIC_HOLIDAY'))
);

create table market_calendar_year (
    market       varchar(2)  not null,
    year         integer     not null,
    published    boolean     not null default false,
    weekend_days varchar(64) not null default 'SATURDAY,SUNDAY',
    constraint pk_market_calendar_year primary key (market, year),
    constraint ck_market_calendar_year_market check (market in ('US', 'ET')),
    constraint ck_market_calendar_year_weekend check (weekend_days ~ '^(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)(,(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY))*$')
);
