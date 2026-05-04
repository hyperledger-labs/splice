-- Drop and recreate app_activity_record_store to add history_id column.
-- see #5320 / #5279 for details
-- In short this denormalization removes potential expensive join to scan_verdict_store

drop table app_activity_record_store;

create table app_activity_record_store
(
    -- History identifier for update history partitioning (same as scan_verdict_store.history_id).
    history_id                  bigint not null,
    -- References the parent verdict in scan_verdict_store
    verdict_row_id              bigint not null,
    -- The mining round number to which this activity is assigned
    round_number                bigint not null,
    -- App providers for which app activity should be recorded
    app_provider_parties        text[] not null,
    -- Activity weight assigned to the app providers (bytes of traffic),
    -- in one-to-one correspondence with app_provider_parties.
    app_activity_weights        bigint[] not null,
    -- One activity record per verdict
    constraint app_activity_record_store_pkey primary key (verdict_row_id),
    -- Referential integrity to parent verdict
    foreign key (verdict_row_id) references scan_verdict_store (row_id)
);

-- Used to compute the per-party totals for a round
create index app_activity_record_store_hi_rn_idx on
  app_activity_record_store (history_id, round_number);

-- Truncate downstream reward-accounting tables; they aggregate from
-- app_activity_record_store and would otherwise be inconsistent with the
-- now-empty activity record table.
truncate table app_activity_party_totals,
               app_activity_round_totals,
               app_reward_party_totals,
               app_reward_round_totals,
               app_reward_batch_hashes,
               app_reward_root_hashes;
