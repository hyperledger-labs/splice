create table interface_views_template
(
    acs_event_number            bigint not null,
    interface_id_package_id     text   not null,
    interface_id_qualified_name text   not null,

    -- the DamlRecord corresponding to the interface's view (if available)
    interface_view              jsonb,
    -- if the view computation failed, this will contain the error for debugging purposes
    view_compute_error          jsonb,
    constraint interface_views_template_has_view_or_error check ((interface_view is null and view_compute_error is not null) or
                                        (view_compute_error is null and interface_view is not null)),

    -- the index will allow querying `WHERE interface_id_qualified_name = some_qualified_interface_id ORDER BY acs_event_number`.
    -- At time of writing, interfaces cannot really be upgraded (therefore the qualified name would change on every upgrade).
    -- However, this might change in the future, so we include the package_id in the primary key.
    -- package_id is in the last position because it's not expected to be used in queries.
    constraint interface_views_template_pkey primary key (interface_id_qualified_name, acs_event_number, interface_id_package_id),
    constraint interface_views_template_acs_evt_num_fkey foreign key (acs_event_number) references acs_store_template (event_number) on delete cascade
);

-- to make deletion of an ACS entry by efficient:
-- some_acs_store will delete the entry and cascade by primary key to this table
create index interface_views_template_aen on interface_views_template (acs_event_number);

create table user_wallet_acs_interface_views
(
    like interface_views_template including all,

    constraint user_wallet_acs_interface_views_acs_evt_num_fkey foreign key (acs_event_number) references user_wallet_acs_store (event_number) on delete cascade
);
