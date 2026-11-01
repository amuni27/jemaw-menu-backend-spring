-- AGF-S2-24 (D-S2-24-1 option A): how often each restaurant's guest menu was opened, per day. An aggregate only: the
-- business, the restaurant's local date (S2-26) and a number. No IP address, cookie, device or other guest data.
-- Deleted with the restaurant. V1-V8 untouched.
create table menu_view_daily (
    business_id varchar(255) not null,
    view_date   date         not null,
    view_count  integer      not null default 0,
    constraint pk_menu_view_daily primary key (business_id, view_date),
    constraint fk_menu_view_daily_business foreign key (business_id) references business (id) on delete cascade,
    constraint ck_menu_view_daily_count check (view_count >= 0)
);
