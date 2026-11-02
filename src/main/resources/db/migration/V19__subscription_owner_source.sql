-- AGF Self-Service Phase A (SS-4): a subscription the owner started themselves (Free with Ads) is recorded with source
-- OWNER: neither a Super Admin manual record (MANUAL, "no payment verified") nor a provider payment (PROVIDER) nor a trial
-- (TRIAL). Only the source check is widened; the trial rules (TRIAL only with source TRIAL), the one-current-subscription
-- index and the guard trigger (source never changes) are unchanged. No data changes. V1-V18 untouched.
alter table business_subscription drop constraint ck_business_subscription_source;
alter table business_subscription add constraint ck_business_subscription_source
    check (source in ('MANUAL', 'PROVIDER', 'TRIAL', 'OWNER'));
