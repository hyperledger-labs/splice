create table sequencer_traffic_summary_store
(
    -- The time when this row was inserted, used for debugging and monitoring
    ingested_at                 timestamptz not null default now(),
    -- History identifier for update history partitioning
    -- The synchronizer-id is included in the history_id via a unique store_name in update_history_descriptors
    history_id                  bigint not null,
    -- Migration identifier for domain migrations
    migration_id                bigint not null,
    -- Time as of which the message was sequenced
    sequencing_time             bigint not null,
    -- Total traffic cost of the message paid by the sender
    total_traffic_cost          bigint not null,
    -- Envelope data as JSONB array: [{"tc": 123, "vid": [1, 2]}, ...]
    -- where "tc" is the traffic cost and "vid" is an array of view_ids from the verdict
    envelopes                   jsonb not null default '[]'::jsonb,
    -- Primary key: (history_id, sequencing_time) uniquely identifies a traffic summary
    primary key (history_id, sequencing_time)
);
