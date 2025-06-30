CREATE TABLE interface_views_template
(
    acs_event_number   bigint not null, -- REFERENCES some_acs_store (event_number) ON DELETE CASCADE,
    interface_id       text   not null,

    -- the DamlRecord corresponding to the interface's view (if available)
    interface_view     jsonb,
    -- if the view computation failed, this will contain the error for debugging purposes
    view_compute_error jsonb,
    CHECK ((interface_view is null and view_compute_error is not null) OR
           (view_compute_error is null and interface_view is not null)),

    -- the index will allow searching by interface_id and order by acs_event_number
    PRIMARY KEY (interface_id, acs_event_number)
);

-- to make deletion of an ACS entry by efficient:
-- some_acs_store will delete the entry and cascade by primary key to this table
CREATE INDEX ON interface_views_template (acs_event_number);

CREATE TABLE user_wallet_acs_interface_views
(
    like interface_views_template including all,

    foreign key (acs_event_number) REFERENCES user_wallet_acs_store (event_number) ON DELETE CASCADE
);
