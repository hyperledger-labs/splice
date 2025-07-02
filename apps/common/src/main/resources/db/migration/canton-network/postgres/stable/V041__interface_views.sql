CREATE TABLE interface_views_template
(
    acs_event_number            bigint not null REFERENCES acs_store_template (event_number) ON DELETE CASCADE,
    interface_id_package_id     text   not null,
    interface_id_qualified_name text   not null,

    -- the DamlRecord corresponding to the interface's view (if available)
    interface_view              jsonb,
    -- if the view computation failed, this will contain the error for debugging purposes
    view_compute_error          jsonb,
    CONSTRAINT has_view_or_error CHECK ((interface_view is null and view_compute_error is not null) OR
                                        (view_compute_error is null and interface_view is not null)),

    -- the index will allow queryiing `WHERE interface_id_qualified_name = some_qualified_interface_id ORDER BY acs_event_number`.
    -- At time of writing, interfaces cannot really be upgraded (therefore the qualified name would change on every upgrade).
    -- However, this might change in the future, so we include the package_id in the primary key.
    -- package_id is in the last position because it's not expected to be used in queries.
    PRIMARY KEY (interface_id_qualified_name, acs_event_number, interface_id_package_id)
);

-- to make deletion of an ACS entry by efficient:
-- some_acs_store will delete the entry and cascade by primary key to this table
CREATE INDEX ON interface_views_template (acs_event_number);

CREATE TABLE user_wallet_acs_interface_views
(
    like interface_views_template including all,

    foreign key (acs_event_number) REFERENCES user_wallet_acs_store (event_number) ON DELETE CASCADE
);
