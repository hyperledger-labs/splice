create table external_party_wallet_acs_store(
    like acs_store_template including all,

    -- reestablish foreign key constraint as that one is not copied by the LIKE statement above
    foreign key (store_id) references store_descriptors(id),

    -- index columns
    ----------------
    -- the round of a reward coupon contract
    reward_coupon_round  bigint
);

create index external_party_wallet_acs_store_sid_mid_tid_rcr
    on external_party_wallet_acs_store (store_id, migration_id, template_id_qualified_name, reward_coupon_round)
    where reward_coupon_round is not null;
