-- AGF-S2-36 (D-CS-2; free, G7/D7): category specials, an additive option next to dish specials (V7 untouched).
-- A whole-number percentage off every guest-visible dish of one category. One per category; deleted with the
-- category. Dates and times are the restaurant's local time (S2-26); business rules are validated in the backend.
create table category_special (
    id               varchar(255) not null,
    business_id      varchar(255) not null,
    category_id      varchar(255) not null,
    discount_percent integer      not null,
    note             varchar(255),
    note_am          varchar(255),
    starts_on        date,
    ends_on          date,
    days_of_week     jsonb        not null,
    start_time       varchar(5),
    end_time         varchar(5),
    is_paused        boolean      not null default false,
    "createdAt"      timestamp(6) with time zone not null,
    "updatedAt"      timestamp(6) with time zone not null,
    constraint pk_category_special primary key (id),
    constraint fk_category_special_business foreign key (business_id) references business (id),
    constraint fk_category_special_category foreign key (category_id) references category (id) on delete cascade,
    constraint uk_category_special_category unique (category_id),
    constraint ck_category_special_percent check (discount_percent between 1 and 90)
);
create index ix_category_special_business on category_special (business_id);

-- Database integrity: a category special must belong to the same restaurant as its category (category -> menu -> business)
create or replace function category_special_business_matches_category() returns trigger as $$
begin
    if not exists (select 1 from category c join menu m on m.id = c.menu_id
                   where c.id = new.category_id and m.business_id = new.business_id) then
        raise exception 'category_special_business_mismatch: category special % and category % belong to different restaurants', new.id, new.category_id;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger category_special_business_matches_category
    before insert or update of business_id, category_id on category_special
    for each row execute function category_special_business_matches_category();
