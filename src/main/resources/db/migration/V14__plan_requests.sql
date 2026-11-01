-- AGF E4-M4: owner plan requests. An owner's choice is a request, never a subscription: the Super Admin fulfils it by
-- recording the E4-M3 manual subscription (linked here) or declines it; the owner can withdraw it. One pending request per
-- restaurant; never a pending request beside a current subscription (invariant I1 backstop); resolved requests are kept
-- and frozen; restaurant, plan, configuration version and requester never change. No prices, payments, trials or limits.
-- V1-V13 untouched.
create table plan_request (
    id                   varchar(36)  not null,
    business_id          varchar(255) not null,
    plan_code            varchar(16)  not null,
    config_version       integer      not null,
    status               varchar(16)  not null,
    requested_at         timestamp(6) with time zone not null,
    requested_by_user_id varchar(255) not null,
    resolved_at          timestamp(6) with time zone,
    resolved_by_user_id  varchar(255),
    subscription_id      varchar(36),
    row_version          integer      not null,
    constraint pk_plan_request primary key (id),
    constraint fk_plan_request_business foreign key (business_id) references business (id),
    constraint fk_plan_request_plan foreign key (plan_code) references subscription_plan (code),
    constraint fk_plan_request_config foreign key (config_version) references monetization_config (version),
    constraint fk_plan_request_subscription foreign key (subscription_id) references business_subscription (id),
    constraint ck_plan_request_status check (status in ('PENDING', 'FULFILLED', 'DECLINED', 'WITHDRAWN')),
    constraint ck_plan_request_resolved check ((status = 'PENDING') = (resolved_at is null)
        and (resolved_at is null) = (resolved_by_user_id is null)),
    constraint ck_plan_request_subscription check ((status = 'FULFILLED') = (subscription_id is not null)),
    constraint ck_plan_request_model check (plan_code <> 'MODEL_1')
);

create unique index uk_plan_request_pending on plan_request (business_id) where status = 'PENDING';
create index ix_plan_request_status on plan_request (status, requested_at desc);

create function plan_request_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'plan requests can''t be deleted';
    end if;
    if tg_op = 'INSERT' then
        if new.status <> 'PENDING' then
            raise exception 'new plan requests must be pending';
        end if;
        if exists (select 1 from business_subscription s where s.business_id = new.business_id and s.status <> 'EXPIRED') then
            raise exception 'a restaurant with a current subscription can''t have a pending plan request';
        end if;
        return new;
    end if;
    if old.status <> 'PENDING' then
        raise exception 'resolved plan requests can''t be changed';
    end if;
    if new.id is distinct from old.id or new.business_id is distinct from old.business_id
            or new.plan_code is distinct from old.plan_code or new.config_version is distinct from old.config_version
            or new.requested_at is distinct from old.requested_at or new.requested_by_user_id is distinct from old.requested_by_user_id then
        raise exception 'restaurant, plan, configuration version and requester can''t be changed';
    end if;
    return new;
end
$$;

create trigger plan_request_guard before insert or update or delete on plan_request
    for each row execute function plan_request_guard();
