-- AGF E4-M8: restaurant-managed ads (Model 3). Each ad belongs to exactly one restaurant (business_id, never changed) and
-- is shown only on that restaurant's guest menu. Content limits as approved (Q-M8-1: title <= 60, text <= 200, optional
-- Amharic text, https links only, optional image with alt text); schedule as UTC instants in the restaurant's time zone
-- (Q-M8-7). Lifecycle (Q-M8-2, Q-M8-9): the owner moves ACTIVE <-> PAUSED and archives; only the Super Admin suspends
-- (reason required) and reinstates (SUSPENDED -> PAUSED), marked by the transaction-local setting agafari.ad_actor = ADMIN.
-- Never deleted directly; archived ads are frozen. No impressions, clicks or revenue are stored. V1-V17 untouched.
create table restaurant_ad (
    id                 varchar(36)   not null,
    business_id        varchar(255)  not null,
    title              varchar(300)  not null,
    body               varchar(1000) not null,
    title_am           varchar(300),
    body_am            varchar(1000),
    link_url           varchar(4096) not null,
    image_key          varchar(512),
    image_alt          varchar(1000),
    starts_at          timestamp(6) with time zone not null,
    ends_at            timestamp(6) with time zone,
    schedule_time_zone varchar(64)   not null,
    status             varchar(16)   not null,
    suspension_reason  varchar(2000),
    suspended_at       timestamp(6) with time zone,
    created_at         timestamp(6) with time zone not null,
    created_by         varchar(255)  not null,
    updated_at         timestamp(6) with time zone not null,
    updated_by         varchar(255)  not null,
    row_version        integer       not null,
    constraint pk_restaurant_ad primary key (id),
    constraint fk_restaurant_ad_business foreign key (business_id) references business (id) on delete cascade,
    constraint ck_restaurant_ad_title check (char_length(btrim(title)) between 1 and 60 and (title_am is null or char_length(title_am) <= 60)),
    constraint ck_restaurant_ad_body check (char_length(btrim(body)) between 1 and 200 and (body_am is null or char_length(body_am) <= 200)),
    constraint ck_restaurant_ad_link check (link_url like 'https://_%' and char_length(link_url) <= 2048),
    constraint ck_restaurant_ad_image check ((image_key is null) = (image_alt is null) and (image_alt is null or char_length(image_alt) between 1 and 200)),
    constraint ck_restaurant_ad_schedule check (ends_at is null or ends_at > starts_at),
    constraint ck_restaurant_ad_status check (status in ('ACTIVE', 'PAUSED', 'SUSPENDED', 'ARCHIVED')),
    constraint ck_restaurant_ad_suspension check ((status = 'SUSPENDED') = (suspension_reason is not null)
        and (status = 'SUSPENDED') = (suspended_at is not null)
        and (suspension_reason is null or char_length(btrim(suspension_reason)) between 1 and 500))
);

create index ix_restaurant_ad_business on restaurant_ad (business_id, status, starts_at);

create function restaurant_ad_guard() returns trigger language plpgsql as $$
declare
    admin boolean := coalesce(current_setting('agafari.ad_actor', true), '') = 'ADMIN';
begin
    if tg_op = 'DELETE' then
        -- only the cascade from a deleted restaurant may remove ads
        if exists (select 1 from business where id = old.business_id) then
            raise exception 'restaurant ads can''t be deleted (archive them instead)';
        end if;
        return old;
    end if;
    if old.status = 'ARCHIVED' then
        raise exception 'archived ads can''t be changed';
    end if;
    if new.id is distinct from old.id or new.business_id is distinct from old.business_id
            or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
        raise exception 'an ad''s id, restaurant and creation can''t be changed';
    end if;
    if new.status is distinct from old.status then
        if (old.status = 'ACTIVE' and new.status = 'PAUSED') or (old.status = 'PAUSED' and new.status = 'ACTIVE')
                or new.status = 'ARCHIVED' then
            null;
        elsif old.status in ('ACTIVE', 'PAUSED') and new.status = 'SUSPENDED' or old.status = 'SUSPENDED' and new.status = 'PAUSED' then
            if not admin then
                raise exception 'only the Super Admin can suspend or reinstate an ad';
            end if;
        else
            raise exception 'invalid ad status change';
        end if;
    end if;
    return new;
end
$$;

create trigger restaurant_ad_guard before update or delete on restaurant_ad
    for each row execute function restaurant_ad_guard();
