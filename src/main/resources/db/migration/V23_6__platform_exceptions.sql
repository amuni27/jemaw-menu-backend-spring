-- AGF EX-F (register §91, PO F-1): platform exceptions. A billing exception may have no restaurant only for a platform code
-- (an unattributable payment, gate 3; a payment outcome left unresolved by a failed financial job, gate 2). Such a row links no
-- restaurant record and is identified durably by its provider event or by the job that failed (source_job_id, unique per
-- code). A source job that names a restaurant (a checkout, refund, payment, subscription or restaurant id in its payload)
-- forces that restaurant: attribution and tenant isolation are never weakened. Existing rows are unchanged.

alter table billing_exception alter column business_id drop not null;
alter table billing_exception add column source_job_id varchar(36);
alter table billing_exception add constraint fk_billing_exception_job foreign key (source_job_id) references job (id);

alter table billing_exception add constraint ck_billing_exception_platform check (
    business_id is not null
    or (code in ('UNATTRIBUTED_PAYMENT', 'PAYMENT_OUTCOME_PENDING_REVIEW')
        and payment_id is null and checkout_id is null and refund_request_id is null
        and (provider_event_id is not null or source_job_id is not null)));
alter table billing_exception add constraint ck_billing_exception_unattributed check (code <> 'UNATTRIBUTED_PAYMENT' or business_id is null);

create unique index uk_billing_exception_job on billing_exception (source_job_id, code) where source_job_id is not null;
create index ix_billing_exception_unresolved on billing_exception (status, due_at) where status <> 'RESOLVED';

-- The restaurant a job's payload names, or null (only provider events and buckets name none).
create function billing_job_restaurant(p_job_id varchar) returns varchar language sql stable as $$
    select coalesce(
        (select c.business_id from checkout_session c where c.id = j.payload ->> 'checkoutId'),
        (select p.business_id from refund_request r join payment p on p.id = r.payment_id where r.id = j.payload ->> 'refundRequestId'),
        (select p.business_id from payment p where p.id = j.payload ->> 'paymentId'),
        (select s.business_id from business_subscription s where s.id = j.payload ->> 'subscriptionId'),
        (select b.id from business b where b.id = j.payload ->> 'businessId'))
    from job j where j.id = p_job_id
$$;

create or replace function billing_exception_guard() returns trigger language plpgsql as $$
declare
    job_restaurant varchar;
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
    -- AGF EX-F: a platform row links no restaurant record (also a check constraint; here so a null can never pass a comparison)
    if new.business_id is null and (new.payment_id is not null or new.checkout_id is not null or new.refund_request_id is not null) then
        raise exception 'a platform exception can''t link a restaurant''s records';
    end if;
    if exists (select 1 from payment p where p.id = new.payment_id and p.business_id is distinct from new.business_id)
            or exists (select 1 from checkout_session c where c.id = new.checkout_id and c.business_id is distinct from new.business_id)
            or exists (select 1 from refund_request r join payment p on p.id = r.payment_id where r.id = new.refund_request_id and p.business_id is distinct from new.business_id) then
        raise exception 'linked records must belong to the exception''s restaurant';
    end if;
    if new.source_job_id is not null then
        job_restaurant := billing_job_restaurant(new.source_job_id);
        if job_restaurant is not null and new.business_id is distinct from job_restaurant then
            raise exception 'the source job belongs to a restaurant; the exception must name that restaurant';
        end if;
    end if;
    return new;
end $$;
