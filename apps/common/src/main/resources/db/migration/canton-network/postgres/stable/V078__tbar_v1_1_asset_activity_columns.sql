-- TBAR v1.1 (CIP-104 amendment): add per-verdict asset-side activity columns
-- plus the two bytes aggregates that mirror the app-side bytes aggregates.
-- All further aggregates (CC values, issuance rate, reward split, thresholded
-- split) are derived at query time from these + the OpenMiningRound config.

alter table app_activity_record_store
    -- Submitting app provider for the verdict. Nullable: unset pre-amendment.
    add column submitting_app_provider          text,
    -- Asset providers for which asset activity should be recorded.
    add column asset_activity_provider_parties  text[],
    -- Asset activity weight (bytes of traffic), one-to-one with
    -- asset_activity_provider_parties; must be NULL iff the parties array is NULL.
    add column asset_activity_weights           bigint[];

alter table app_activity_party_totals
    -- Per-party asset activity bytes for the round. Mirrors
    -- total_app_activity_weight.
    add column total_asset_activity_weight bigint not null default 0;

alter table app_activity_round_totals
    -- Round-wide asset activity bytes. Mirrors total_round_app_activity_weight.
    add column total_round_asset_activity_weight bigint not null default 0;

alter table app_reward_round_totals
    -- Total CC allocated for app rewards this round (incl thresholded).
    -- = featuredAppMintingAllowanceCC per CIP-104.
    add column total_app_reward                     decimal(38,10) not null default 0,
    -- Total CC allocated for asset rewards this round (incl thresholded).
    -- = featuredAssetMintingAllowanceCC per CIP-104.
    add column total_asset_reward                   decimal(38,10) not null default 0,
    -- CC-minted-per-CC-burned ratio for featured app activity.
    add column issuance_per_featured_app_activity   decimal(38,10) not null default 0,
    -- CC-minted-per-CC-burned ratio for featured asset activity.
    add column issuance_per_featured_asset_activity decimal(38,10) not null default 0;
