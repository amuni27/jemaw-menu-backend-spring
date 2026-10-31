-- AGF-S2-25 (D-S2-25-2, G7/D7: free for every restaurant): time-bound specials. One special per dish, deleted with
-- the dish. Times and dates are the restaurant's local time (S2-26); business rules are validated in the backend.
create table special (
    id              varchar(255)  not null,
    business_id     varchar(255)  not null,
    menu_item_id    varchar(255)  not null,
    special_price   numeric(10,2) not null,
    note            varchar(255),
    note_am         varchar(255),
    starts_on       date,
    ends_on         date,
    days_of_week    jsonb         not null,
    start_time      varchar(5),
    end_time        varchar(5),
    is_paused       boolean       not null default false,
    "createdAt"     timestamp(6) with time zone not null,
    "updatedAt"     timestamp(6) with time zone not null,
    constraint pk_special primary key (id),
    constraint fk_special_business foreign key (business_id) references business (id),
    constraint fk_special_menu_item foreign key (menu_item_id) references menu_item (id) on delete cascade,
    constraint uk_special_menu_item unique (menu_item_id),
    constraint ck_special_price check (special_price >= 0)
);
create index ix_special_business on special (business_id);

-- Database integrity (PO clarification): a special must belong to the same restaurant as its dish
-- (dish -> menu -> business). The column can't be a composite foreign key because menu_item has no business_id.
create or replace function special_business_matches_dish() returns trigger as $$
begin
    if not exists (select 1 from menu_item i join menu m on m.id = i.menu_id
                   where i.id = new.menu_item_id and m.business_id = new.business_id) then
        raise exception 'special_business_mismatch: special % and dish % belong to different restaurants', new.id, new.menu_item_id;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger special_business_matches_dish
    before insert or update of business_id, menu_item_id on special
    for each row execute function special_business_matches_dish();
