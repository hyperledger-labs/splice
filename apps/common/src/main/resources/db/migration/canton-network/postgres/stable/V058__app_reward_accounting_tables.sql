-- Per-party totals of app activity weights for each round.
create table app_activity_party_totals
(
    -- History identifier for update history partitioning (same as update_history_id)
    history_id                  bigint not null,
    -- The mining round number for which the totals are computed
    round_number                bigint not null,
    -- Total activity weight recorded for the app provider in the given round.
    -- Measured in bytes of traffic.
    total_app_activity_weight   bigint not null,
    -- Sequence number of the app provider party in the given round.
    -- Assigned in ascending order of app_provider_party starting from 0 for each round.
    -- Used as a compact reference to the app provider party in other tables to save space,
    -- as the party identifier can be long.
    app_provider_party_seq_num  int not null,
    -- The app provider party for which the totals are computed
    app_provider_party          text not null,

    primary key (history_id, round_number, app_provider_party_seq_num),

    -- Uniqueness constraint and index to lookup activity totals by app provider party
    constraint uq_app_activity_party unique (history_id, round_number, app_provider_party)
);

-- Per-round totals of app activity weights across all app providers.
-- Used to determine the total amount of app rewards to be distributed in a round based on the total activity and the reward curve.
create table app_activity_round_totals
(
    -- History identifier for update history partitioning (same as update_history_id)
    history_id                            bigint not null,
    -- The mining round number for which the totals are computed
    round_number                          bigint not null,
    -- Total activity weight recorded across all app providers in the given round.
    -- Measured in bytes of traffic.
    total_round_app_activity_weight       bigint not null,
    -- Number of parties with non-zero activity in the given round.
    -- Used for debugging purposes.
    active_app_provider_parties_count     bigint not null,

    primary key (history_id, round_number)
);

-- Per-party totals of app rewards for each round.
create table app_reward_party_totals
(
    -- History identifier for update history partitioning (same as update_history_id)
    history_id                  bigint not null,
    -- The mining round number for which the totals are computed
    round_number                bigint not null,
    -- Party number within the round.
    app_provider_party_seq_num  int not null,

    -- Total app reward amount minting allowance for the app provider in the given round.
    -- Measured in Amulet.
    total_app_reward_amount     decimal(38,10) not null,

    primary key (history_id, round_number, app_provider_party_seq_num),

    foreign key (history_id, round_number, app_provider_party_seq_num)
      references app_activity_party_totals (history_id, round_number, app_provider_party_seq_num)
);

-- Per-round totals of app rewards across all app providers.
-- Used for debugging purposes only.
create table app_reward_round_totals
(
    -- History identifier for update history partitioning (same as update_history_id)
    history_id                              bigint not null,
    -- The mining round number for which the totals are computed
    round_number                            bigint not null,

    -- Total app reward amount minting allowance across all app providers in the given round.
    -- Measured in Amulet.
    total_app_reward_minting_allowance      decimal(38,10) not null,

    -- Total amount of app rewards that were burned due to being below the threshold.
    total_app_reward_thresholded            decimal(38,10) not null,

    -- Total amount of app rewards that were unclaimed (i.e., for which there was no rewardable activity).
    total_app_reward_unclaimed              decimal(38,10) not null,

    -- Number of parties with non-zero reward in the given round.
    -- These can be fewer than the active_app_provider_parties_count in app_activity_round_totals,
    -- as rewards below the threshold are burned.
    rewarded_app_provider_parties_count     bigint not null,

    primary key (history_id, round_number)
);


create table app_reward_batch_hashes
(
    -- History identifier for update history partitioning (same as update_history_id)
    history_id                    bigint not null,
    -- The mining round number for which the batch hashes are recorded
    round_number                  bigint not null,
    -- The level of the batch for the given round.
    -- Levels are assigned in ascending order starting from 0 for each round,
    -- with each batch containing a contiguous sequence of parties based on their app_provider_party_seq_num.
    --
    -- Child batches can be found by looking for batches with the same
    -- round_number and a lower batch_level and have a party_seq_num_begin_incl that is
    -- within the party_seq_num_begin_incl and party_seq_num_end_excl of the parent batch.
    batch_level                   int not null,
    -- Sequence number of the first party in the batch (inclusive)
    party_seq_num_begin_incl      int not null,
    -- Sequence number of the last party in the batch (exclusive);
    -- always matches the party_seq_num_begin_incl of the next batch for the same round and level,
    -- unless this is the last batch.
    party_seq_num_end_excl        int not null,
    -- The hash of the batch of app rewards for the given round. Used for
    -- verifyable on-ledger reward coupon creation.
    batch_hash                    bytea not null,

    primary key (history_id, round_number, batch_level, party_seq_num_begin_incl)
);

create index idx_app_reward_batch_hash_by_hash on app_reward_batch_hashes (history_id, round_number, batch_hash);


create table app_reward_root_hashes
(
    -- History identifier for update history partitioning (same as update_history_id)
    history_id    bigint not null,
    -- The mining round number for which the root hashes are recorded
    round_number  bigint not null,
    -- The hash of the Merkle root of the app reward batches for the given round.
    -- Used for verifyable on-ledger reward coupon creation.
    --
    -- The root hash is the single batch with the maximal level.
    root_hash     bytea not null,

    primary key (history_id, round_number)
);
