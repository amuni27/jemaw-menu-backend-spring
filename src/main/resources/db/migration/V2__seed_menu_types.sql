-- AGF-S1-09 (PO-1): the nine approved menu types, as reference data.
--
-- Inserts only names that don't exist yet, compared case-insensitively; never updates or deletes existing rows, so
-- menus keep their types and an existing "lunch" stays as it is (it isn't duplicated as "Lunch").
--
-- IDs are fixed and readable (D-S1-09-1). "updatedAt" carries each type's time of day in Addis Ababa time (D3,
-- D-S1-09-2): PublicMenuServiceImpl.sortMenusByClosestMenuTypeTimeOfDay() lists first the menu whose type time is
-- closest to now. Server time and stored time are compared in the same zone, so these act as Addis meal times.
insert into menu_type (id, name, "createdAt", "updatedAt")
select v.id, v.name, timestamptz '2026-01-01 00:00:00+03', v.updated_at
from (values
    ('mt-breakfast', 'Breakfast', timestamptz '2026-01-01 07:00:00+03'),
    ('mt-brunch',    'Brunch',    timestamptz '2026-01-01 10:00:00+03'),
    ('mt-kids',      'Kids',      timestamptz '2026-01-01 11:30:00+03'),
    ('mt-lunch',     'Lunch',     timestamptz '2026-01-01 12:30:00+03'),
    ('mt-all-day',   'All Day',   timestamptz '2026-01-01 13:00:00+03'),
    ('mt-specials',  'Specials',  timestamptz '2026-01-01 14:00:00+03'),
    ('mt-desserts',  'Desserts',  timestamptz '2026-01-01 15:30:00+03'),
    ('mt-drinks',    'Drinks',    timestamptz '2026-01-01 17:00:00+03'),
    ('mt-dinner',    'Dinner',    timestamptz '2026-01-01 19:00:00+03')
) as v(id, name, updated_at)
where not exists (select 1 from menu_type t where lower(t.name) = lower(v.name));
