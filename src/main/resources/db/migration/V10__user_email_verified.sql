-- AGF-S2-22: when the owner proved control of their email (verification link). Null = not verified. Nullable with no
-- default. V1-V9 untouched.
alter table users add column email_verified_at timestamp(6) with time zone;
-- D-S2-22-5: owners who registered before verification existed are grandfathered as verified (their registration time)
update users set email_verified_at = "createdAt" where email_verified_at is null;
