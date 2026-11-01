-- AGF E4-M2: the platform-wide monetization configuration chosen by the Super Admin. Every change is a new, immutable
-- version (the highest version is current); rows can't be changed or deleted (trigger), so the history stays available
-- for grandfathering (E4-M6). Setting 1 = Model 1 only; Setting 2 = any nonempty combination of Models 2, 3 and 4.
-- Starts empty ("not configured"). No restaurant data is touched. V1-V11 untouched.
create table monetization_config (
    version            integer      not null,
    setting            varchar(16)  not null,
    model_2_enabled    boolean      not null default false,
    model_3_enabled    boolean      not null default false,
    model_4_enabled    boolean      not null default false,
    effective_from     timestamp(6) with time zone not null,
    created_at         timestamp(6) with time zone not null,
    created_by_user_id varchar(255) not null,
    constraint pk_monetization_config primary key (version),
    constraint ck_monetization_config_version check (version >= 1),
    constraint ck_monetization_config_setting check (
        (setting = 'SETTING_1' and not model_2_enabled and not model_3_enabled and not model_4_enabled)
        or (setting = 'SETTING_2' and (model_2_enabled or model_3_enabled or model_4_enabled))),
    constraint fk_monetization_config_created_by foreign key (created_by_user_id) references users (id)
);

create function monetization_config_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'monetization_config is append-only';
end
$$;

create trigger monetization_config_no_change before update or delete on monetization_config
    for each row execute function monetization_config_append_only();
