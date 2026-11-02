-- AGF E4-M7: Agafari-managed ads (Model 2). The Super Admin's ad inventory within the E4-Q5 minimum policy (title <= 60,
-- text <= 200, optional Amharic text, https links only, optional image with alt text) and a schedule stored as UTC
-- instants (start inclusive, end exclusive; D-M7-1) with the zone it was entered in (display only). Ads are never deleted;
-- archived ads are frozen. Served impressions are an aggregate per ad, restaurant and restaurant-local day (the S2-24
-- approach): no IP address, cookie, device or other guest data. V1-V16 untouched.
create table agafari_ad (
    id                 varchar(36)   not null,
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
    created_at         timestamp(6) with time zone not null,
    created_by         varchar(255)  not null,
    updated_at         timestamp(6) with time zone not null,
    updated_by         varchar(255)  not null,
    row_version        integer       not null,
    constraint pk_agafari_ad primary key (id),
    constraint ck_agafari_ad_title check (char_length(btrim(title)) between 1 and 60 and (title_am is null or char_length(title_am) <= 60)),
    constraint ck_agafari_ad_body check (char_length(btrim(body)) between 1 and 200 and (body_am is null or char_length(body_am) <= 200)),
    constraint ck_agafari_ad_link check (link_url like 'https://_%' and char_length(link_url) <= 2048),
    constraint ck_agafari_ad_image check ((image_key is null) = (image_alt is null) and (image_alt is null or char_length(image_alt) between 1 and 200)),
    constraint ck_agafari_ad_schedule check (ends_at is null or ends_at > starts_at),
    constraint ck_agafari_ad_status check (status in ('ACTIVE', 'PAUSED', 'ARCHIVED'))
);

create index ix_agafari_ad_servable on agafari_ad (status, starts_at);

create table ad_impression_daily (
    ad_id            varchar(36)  not null,
    business_id      varchar(255) not null,
    view_date        date         not null,
    impression_count integer      not null default 0,
    constraint pk_ad_impression_daily primary key (ad_id, business_id, view_date),
    constraint fk_ad_impression_daily_ad foreign key (ad_id) references agafari_ad (id),
    constraint fk_ad_impression_daily_business foreign key (business_id) references business (id) on delete cascade,
    constraint ck_ad_impression_daily_count check (impression_count >= 0)
);

create function agafari_ad_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'Agafari ads can''t be deleted (archive them instead)';
    end if;
    if old.status = 'ARCHIVED' then
        raise exception 'archived ads can''t be changed';
    end if;
    if new.id is distinct from old.id or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
        raise exception 'an ad''s id and creation can''t be changed';
    end if;
    if new.status is distinct from old.status and not (new.status = 'ARCHIVED'
            or (old.status = 'ACTIVE' and new.status = 'PAUSED') or (old.status = 'PAUSED' and new.status = 'ACTIVE')) then
        raise exception 'invalid ad status change';
    end if;
    return new;
end
$$;

create trigger agafari_ad_guard before update or delete on agafari_ad
    for each row execute function agafari_ad_guard();
