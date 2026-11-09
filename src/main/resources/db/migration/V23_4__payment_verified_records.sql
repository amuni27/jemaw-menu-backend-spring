-- AGF V23.4 (PO D1 and D3, register §12; R1, register §21): the verified-payment rule, on the row itself.
-- Additive: no row changes. Incompatible records stop the migration; they are never rewritten, deleted or repaired (AD-19).
-- REFUND and CHARGEBACK are otherwise left as they are while AD-9 and PD-7 are open.

lock table payment in access exclusive mode;

do $$
begin
    if exists (select 1 from payment
               where (status = 'SUCCEEDED' and (verified_at is null or verification is null or verification !~ '[^[:space:]]'))
                  or (kind = 'CHARGE' and (status <> 'SUCCEEDED' or verification is null
                      or verification not in ('SIGNED_WEBHOOK+API_FETCH', 'RECONCILIATION_API_FETCH')))) then
        raise exception 'V23.4 refused: payments exist that are not verified records';
    end if;
end $$;

-- The explicit null tests matter: a CHECK passes when its expression is null.
alter table payment add constraint ck_payment_success_evidence
    check (status <> 'SUCCEEDED' or (verified_at is not null and verification is not null and verification ~ '[^[:space:]]'));

alter table payment add constraint ck_payment_charge_verified
    check (kind <> 'CHARGE' or (status = 'SUCCEEDED' and verification is not null
           and verification in ('SIGNED_WEBHOOK+API_FETCH', 'RECONCILIATION_API_FETCH')));
