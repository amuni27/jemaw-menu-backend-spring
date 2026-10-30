-- AGF-S2-12 CR1 (D-CR1-2): the restaurant's country decides which location fields apply (ET: Google Maps link;
-- US: street, city, state, ZIP). Every existing restaurant was registered through the Ethiopia-only form, so the
-- default makes them ET; no other data changes. Values are validated in the backend (no check constraint).
alter table business add column country varchar(2) not null default 'ET';
