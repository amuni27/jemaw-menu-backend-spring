-- AGF-S2-12: the restaurant's location as a validated Google Maps share link (D-REV-4: at most 2,048 characters).
-- Nullable without a default: existing restaurants simply have no link and nothing else changes. No other columns
-- (D-S2-12-1: no sub-city, woreda, kebele or house-number columns).
alter table business add column location_url varchar(2048);
