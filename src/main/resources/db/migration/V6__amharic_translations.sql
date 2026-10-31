-- AGF-S2-23 (D-S2-23-1, -7): optional Amharic translations of guest-visible names and descriptions.
-- Nullable, no default: existing menus show exactly as before. Same 255-character limit as the main text.
alter table menu      add column name_am        varchar(255);
alter table category  add column name_am        varchar(255);
alter table menu_item add column name_am        varchar(255);
alter table menu_item add column description_am varchar(255);
