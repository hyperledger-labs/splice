create table round_total_amulet_balance
(
    -- the store id of the scan store for which the totals are calculated
    store_id             int not null,
    -- the closed round
    closed_round         bigint not null,
    -- the total amulet balance as of closed_round
    total_amulet_balance numeric,
    primary key (store_id, closed_round)
);

create table wallet_balances
(
    -- the store id of the scan store for which the totals are calculated
    store_id     int not null,
    -- the closed round when the balance is calculated
    closed_round bigint not null,
    -- the party whose wallet balance is tracked
    party        text not null,
    -- the amulet balance of the party's wallet as of closed_round
    amulet_balance numeric,
    primary key (store_id, party, closed_round)
);

create table ranked_providers_by_app_rewards
(
    -- the store id of the scan store for which leaderboard is calculated
    store_id               int not null,
    -- the closed round of the leaderboard
    closed_round           bigint not null,
    -- the provider party
    party                  text not null,
    -- the cumulative app rewards of the provider as of closed_round
    cumulative_app_rewards numeric not null,
    -- the rank of the provider in the leaderboard as of closed_round
    rank_nr                int not null,
    primary key (store_id, party, closed_round)
);

-- for efficient retrieval of the getTopProvidersByAppRewards leaderboard for a given store and round
create index idx_ranked_providers_by_app_rewards_store_round
    on ranked_providers_by_app_rewards (store_id, closed_round, rank_nr asc);
