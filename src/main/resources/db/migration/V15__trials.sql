-- AGF E4-M5: 30-day Model 1 trials are business_subscription rows with source TRIAL (not a Super Admin manual record and
-- not a verified payment). A trial always has an end date; TRIAL status only comes from a trial; at most one trial per
-- restaurant, ever (rows are never deleted). The V13 lifecycle rules (one current row, frozen history, immutable
-- plan/config version/restaurant/source, current rows may only be ended) apply unchanged. V1-V14 untouched.
alter table business_subscription drop constraint ck_business_subscription_source;
alter table business_subscription add constraint ck_business_subscription_source
    check (source in ('MANUAL', 'PROVIDER', 'TRIAL'));

alter table business_subscription add constraint ck_business_subscription_trial_source
    check ((source <> 'TRIAL' or (plan_code = 'MODEL_1' and status in ('TRIAL', 'EXPIRED')))
       and (status <> 'TRIAL' or source = 'TRIAL'));

alter table business_subscription add constraint ck_business_subscription_trial_end
    check (status <> 'TRIAL' or period_end is not null);

create unique index uk_business_subscription_one_trial on business_subscription (business_id) where source = 'TRIAL';
