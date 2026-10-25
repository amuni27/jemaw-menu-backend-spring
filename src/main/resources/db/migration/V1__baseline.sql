-- Agafari schema, version 1 (provisional baseline, PO Decision A, 2026-10-25).
--
-- Exactly the schema Hibernate builds from the current entities (exported on PostgreSQL 16; columns, types, keys,
-- unique rules and check constraints match; ExistingDatabaseUpgradeTest compares them). Only constraint names differ:
-- they are readable here, and Hibernate's validation does not check names.
--
-- Fresh databases run this file. Existing development databases built by ddl-auto are recorded at version 1 without
-- running it (spring.flyway.baseline-on-migrate), so their data stays. Never edit this file once a shared database has
-- applied it: every later change is a new V2, V3, ... migration (see docs/AGAFARI_DECISION_A_PROVISIONAL_BASELINE_REPORT.md).

create table users (
    "createdAt"   timestamp(6) with time zone not null,
    "updatedAt"   timestamp(6) with time zone not null,
    email         varchar(255) not null,
    full_name     varchar(255) not null,
    id            varchar(255) not null,
    password_hash varchar(255) not null,
    phone_number  varchar(255),
    role          varchar(255) not null,
    status        varchar(255) not null,
    constraint pk_users primary key (id),
    constraint uk_users_email unique (email),
    constraint ck_users_role check (role in ('VENUE', 'ADMIN')),
    constraint ck_users_status check (status in ('ACTIVE', 'INACTIVE', 'SUSPENDED'))
);

create table business (
    open24_7         boolean not null,
    "createdAt"      timestamp(6) with time zone not null,
    "updatedAt"      timestamp(6) with time zone not null,
    business_phone   varchar(255) not null,
    city             varchar(255) not null,
    currency         varchar(255) not null,
    custom_subdomain varchar(255) not null,
    id               varchar(255) not null,
    logo_key         varchar(255),
    name             varchar(255) not null,
    owner_user_id    varchar(255) not null,
    state            varchar(255) not null,
    street_address   varchar(255) not null,
    zipcode          varchar(255) not null,
    constraint pk_business primary key (id),
    constraint uk_business_custom_subdomain unique (custom_subdomain),
    constraint uk_business_owner_user_id unique (owner_user_id),
    constraint fk_business_owner foreign key (owner_user_id) references users (id)
);

create table business_hours (
    is_open     boolean not null,
    "createdAt" timestamp(6) with time zone not null,
    "updatedAt" timestamp(6) with time zone not null,
    business_id varchar(255) not null,
    day_of_week varchar(255) not null,
    end_time    varchar(255),
    id          varchar(255) not null,
    start_time  varchar(255),
    constraint pk_business_hours primary key (id),
    constraint fk_business_hours_business foreign key (business_id) references business (id),
    constraint ck_business_hours_day check (day_of_week in ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'))
);

create table menu_type (
    "createdAt" timestamp(6) with time zone not null,
    "updatedAt" timestamp(6) with time zone not null,
    id          varchar(255) not null,
    name        varchar(255) not null,
    constraint pk_menu_type primary key (id),
    constraint uk_menu_type_name unique (name)
);

create table menu (
    is_active    boolean not null,
    "createdAt"  timestamp(6) with time zone not null,
    "updatedAt"  timestamp(6) with time zone not null,
    business_id  varchar(255) not null,
    description  varchar(255),
    id           varchar(255) not null,
    menu_type_id varchar(255) not null,
    name         varchar(255) not null,
    visibility   varchar(255) not null,
    constraint pk_menu primary key (id),
    constraint fk_menu_business foreign key (business_id) references business (id),
    constraint fk_menu_menu_type foreign key (menu_type_id) references menu_type (id),
    constraint ck_menu_visibility check (visibility in ('PUBLIC', 'PRIVATE'))
);

create table category (
    is_active   boolean not null,
    sort_order  integer not null,
    "createdAt" timestamp(6) with time zone not null,
    "updatedAt" timestamp(6) with time zone not null,
    id          varchar(255) not null,
    menu_id     varchar(255) not null,
    name        varchar(255) not null,
    constraint pk_category primary key (id),
    constraint fk_category_menu foreign key (menu_id) references menu (id)
);

create table menu_item (
    calories          integer,
    is_featured       boolean not null,
    prep_time_minutes integer,
    price             numeric(10, 2) not null,
    sort_order        integer not null,
    "createdAt"       timestamp(6) with time zone not null,
    "updatedAt"       timestamp(6) with time zone not null,
    category_id       varchar(255) not null,
    description       varchar(255),
    id                varchar(255) not null,
    image_url         varchar(255),
    menu_id           varchar(255) not null,
    name              varchar(255) not null,
    spice_level       varchar(255),
    status            varchar(255) not null,
    allergens         jsonb not null,
    ingredients       jsonb,
    tags              jsonb not null,
    constraint pk_menu_item primary key (id),
    constraint fk_menu_item_category foreign key (category_id) references category (id),
    constraint fk_menu_item_menu foreign key (menu_id) references menu (id),
    constraint ck_menu_item_spice_level check (spice_level in ('NONE', 'MILD', 'MEDIUM', 'HOT', 'EXTRA_HOT')),
    constraint ck_menu_item_status check (status in ('AVAILABLE', 'UNAVAILABLE', 'OUT_OF_STOCK'))
);
