create table round_total_amulet_balance
(
    -- the store id of the scan store for which the totals are calculated
    store_id             int not null,
    -- the closed round
    closed_round         bigint not null,
    -- the total amulet balance as of closed_round
    sum_cumulative_change_to_initial_amount_as_of_round_zero numeric not null,
    sum_cumulative_change_to_holding_fees_rate numeric not null,
    primary key (store_id, closed_round)
);

-- round_total_amulet_balance does not have a sums for rounds when nothing changed.
-- this index is for efficiently finding an earlier round and calculating the total balance as of a given round
create index idx_round_total_amulet_balance_sid_cr_desc
    on round_total_amulet_balance (store_id, closed_round desc);
