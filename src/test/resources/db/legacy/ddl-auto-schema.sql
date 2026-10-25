-- Decision A test fixture: the schema an existing development database has today, exactly as Hibernate
-- ddl-auto creates it from the current entities (exported 2026-10-25). Used to prove Flyway baselines such a
-- database without running V1 and without touching its data, and that V1 builds the same columns.

create table business (
    open24_7 boolean not null,
    "createdAt" timestamp(6) with time zone not null,
    "updatedAt" timestamp(6) with time zone not null,
    business_phone varchar(255) not null,
    city varchar(255) not null,
    currency varchar(255) not null,
    custom_subdomain varchar(255) not null unique,
    id varchar(255) not null,
    logo_key varchar(255),
    name varchar(255) not null,
    owner_user_id varchar(255) not null unique,
    state varchar(255) not null,
    street_address varchar(255) not null,
    zipcode varchar(255) not null,
    primary key (id)
);

create table business_hours (
    is_open boolean not null,
    "createdAt" timestamp(6) with time zone not null,
    "updatedAt" timestamp(6) with time zone not null,
    business_id varchar(255) not null,
    day_of_week varchar(255) not null check ((day_of_week in ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'))),
    end_time varchar(255),
    id varchar(255) not null,
    start_time varchar(255),
    primary key (id)
);

create table category (
    is_active boolean not null,
    sort_order integer not null,
    "createdAt" timestamp(6) with time zone not null,
    "updatedAt" timestamp(6) with time zone not null,
    id varchar(255) not null,
    menu_id varchar(255) not null,
    name varchar(255) not null,
    primary key (id)
);

create table menu (
    is_active boolean not null,
    "createdAt" timestamp(6) with time zone not null,
    "updatedAt" timestamp(6) with time zone not null,
    business_id varchar(255) not null,
    description varchar(255),
    id varchar(255) not null,
    menu_type_id varchar(255) not null,
    name varchar(255) not null,
    visibility varchar(255) not null check ((visibility in ('PUBLIC','PRIVATE'))),
    primary key (id)
);

create table menu_item (
    calories integer,
    is_featured boolean not null,
    prep_time_minutes integer,
    price numeric(10,2) not null,
    sort_order integer not null,
    "createdAt" timestamp(6) with time zone not null,
    "updatedAt" timestamp(6) with time zone not null,
    category_id varchar(255) not null,
    description varchar(255),
    id varchar(255) not null,
    image_url varchar(255),
    menu_id varchar(255) not null,
    name varchar(255) not null,
    spice_level varchar(255) check ((spice_level in ('NONE','MILD','MEDIUM','HOT','EXTRA_HOT'))),
    status varchar(255) not null check ((status in ('AVAILABLE','UNAVAILABLE','OUT_OF_STOCK'))),
    allergens jsonb not null,
    ingredients jsonb,
    tags jsonb not null,
    primary key (id)
);

create table menu_type (
    "createdAt" timestamp(6) with time zone not null,
    "updatedAt" timestamp(6) with time zone not null,
    id varchar(255) not null,
    name varchar(255) not null unique,
    primary key (id)
);

create table users (
    "createdAt" timestamp(6) with time zone not null,
    "updatedAt" timestamp(6) with time zone not null,
    email varchar(255) not null unique,
    full_name varchar(255) not null,
    id varchar(255) not null,
    password_hash varchar(255) not null,
    phone_number varchar(255),
    role varchar(255) not null check ((role in ('VENUE','ADMIN'))),
    status varchar(255) not null check ((status in ('ACTIVE','INACTIVE','SUSPENDED'))),
    primary key (id)
);

alter table if exists business 
   add constraint FK4q110jvhmst3bpiwn75slgo8y 
   foreign key (owner_user_id) 
   references users;

alter table if exists business_hours 
   add constraint FKptkel1plhq5brh8114hajmxwv 
   foreign key (business_id) 
   references business;

alter table if exists category 
   add constraint FK7ld4ysop2r15rbwxiue1ko5eb 
   foreign key (menu_id) 
   references menu;

alter table if exists menu 
   add constraint FKni8a5ckufsaltw6t8bhpexwnk 
   foreign key (business_id) 
   references business;

alter table if exists menu 
   add constraint FKcdd1u1s7s00rxncjlalwm13d6 
   foreign key (menu_type_id) 
   references menu_type;

alter table if exists menu_item 
   add constraint FKfmgn0386uxf8sqtilegkufxk0 
   foreign key (category_id) 
   references category;

alter table if exists menu_item 
   add constraint FKcdkmv42yhn6udah6ug8rsymfl 
   foreign key (menu_id) 
   references menu;
