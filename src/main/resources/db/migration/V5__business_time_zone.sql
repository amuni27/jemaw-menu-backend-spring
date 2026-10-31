-- AGF-S2-26 (D-S2-26-2): the restaurant's IANA time zone for "open now", today's hours and menu ordering. Every
-- existing restaurant is Ethiopian (V4 default), so the default keeps them on Africa/Addis_Ababa (D3). No other change.
alter table business add column time_zone varchar(64) not null default 'Africa/Addis_Ababa';
