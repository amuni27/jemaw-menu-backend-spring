-- AGF gate 3 (register §102 M-1): the verified payment an exception is about, so that an unattributable payment has exactly
-- one UNATTRIBUTED_PAYMENT however many webhooks or reconciliation runs deliver it. Additive: existing rows keep both
-- columns null and existing inserts are unchanged. The columns are immutable after insert by billing_exception_guard (every
-- column outside its review-workflow list). Flyway runs this file in one transaction; PostgreSQL DDL is transactional, so a
-- failure leaves no part of it behind.

alter table billing_exception add column payment_provider varchar(32);
alter table billing_exception add column provider_payment_ref varchar(255);

alter table billing_exception add constraint ck_billing_exception_payment_identity
    check ((payment_provider is null) = (provider_payment_ref is null));

create unique index uk_billing_exception_provider_payment
    on billing_exception (payment_provider, provider_payment_ref, code)
    where provider_payment_ref is not null;
