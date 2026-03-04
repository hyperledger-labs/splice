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
-- Each row represents the traffic-weighted activity of featured app providers for a given verdict.
-- It is derived from a computation involving data in its parent table, scan_verdict_store.
--
-- Each record references its parent verdict by verdict_row_id (FK to scan_verdict_store.row_id),
-- ensuring referential integrity and allowing joins without denormalized columns.
create table app_activity_record_store
(
    -- References the parent verdict in scan_verdict_store
    verdict_row_id              bigint not null,
    -- The mining round number to which this activity is assigned
    round_number                bigint not null,
    -- App providers for which app activity should be recorded
    app_provider_parties        text[] not null,
    -- Activity weight assigned to the app providers.
    -- Measured in bytes of traffic.
    -- Values are in one-to-one correspondence with the values in the app_provider_parties array.
    app_activity_weights        bigint[] not null,
    -- One activity record per verdict
    constraint app_activity_record_store_pkey primary key (verdict_row_id),
    -- Referential integrity to parent verdict
    foreign key (verdict_row_id) references scan_verdict_store (row_id)
);


-- Used to compute the per-party totals for a round
create index app_activity_record_store_round_nr_idx on
  app_activity_record_store (round_number);
