-- Add columns for storing total_traffic_cost and envelope traffic cost data to the scan_verdict_store table
--
-- This is done as the traffic summary data is treated as extra data attached to
-- the mediator verdict
-- They are declared null so their addition does not require a hard migration
alter table scan_verdict_store
    -- Total traffic cost of the message paid by the sender
    add column total_traffic_cost          bigint null,
    -- Envelope traffic cost data as JSONB array: [{"tc": 123, "vid": [1, 2]}, ...]
    -- where "tc" is the traffic cost and "vid" is an array of view_ids from the verdict
    add column envelope_traffic_costs       jsonb null;


-- Stores computed app activity records derived from verdicts and traffic summaries.
-- Each row represents the traffic-weighted activity of featured app providers at a given record_time.
-- and is derived from a computation involving the data in it's parent table, scan_event_store
--
-- The history_id and record_time are denormalized from scan_event_store as they form a key and allow
-- this data to be served without joining on it
create table app_activity_record_store
(
    -- History identifier for update history partitioning (same as sequencer_traffic_summary_store)
    history_id                  bigint not null,
    -- The record_time (= sequencing_time) of the verdict/traffic summary
    record_time                 bigint not null,
    -- The mining round number to which this activity is assigned
    round_number                bigint not null,
    -- App providers for which app activity should be recorded
    app_provider_parties        text[] not null,
    -- Activity weight assigned to the app providers.
    -- Measured in bytes of traffic.
    -- Values are in one-to-one correspondence with the values in the app_provider_parties array.
    app_activity_weights        bigint[] not null,
    -- Primary key: (history_id, record_time) uniquely identifies an activity record
    primary key (history_id, record_time)
);


-- Used to compute the per-party totals for a round
create index app_activity_record_store_round_nr_idx on
  app_activity_record_store (history_id, round_number);
