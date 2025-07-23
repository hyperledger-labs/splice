create table interface_views_template
(
    acs_event_number            bigint not null,
    interface_id_package_id     text   not null,
    -- the qualified name of the definition of the interface. Does NOT include the package name.
    -- At time of writing, interfaces cannot be upgraded using SCU.
    -- The only option is to introduce new interface definitions,
    -- and thus the qualified name will change on every upgrade.
    interface_id_qualified_name text   not null,

    -- the DamlRecord corresponding to the interface's view
    interface_view              jsonb not null,

    -- the index will allow querying `WHERE interface_id_package_id = ? AND interface_id_qualified_name = ? ORDER BY acs_event_number`.
    constraint interface_views_template_pkey primary key (interface_id_package_id, interface_id_qualified_name, acs_event_number),
    constraint interface_views_template_acs_evt_num_fkey foreign key (acs_event_number) references acs_store_template (event_number) on delete cascade
);

-- to make deletion of an ACS entry by efficient (either manually, or by the CASCADE in the referenced store table):
-- `delete from interface_views_template where acs_event_number = some_acs_event_number`
create index interface_views_template_aen on interface_views_template (acs_event_number);

create table user_wallet_acs_interface_views
(
    like interface_views_template including all,

    constraint user_wallet_acs_interface_views_acs_evt_num_fkey foreign key (acs_event_number) references user_wallet_acs_store (event_number) on delete cascade
);
