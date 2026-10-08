-- TBAR v1.1 (CIP-104 amendment): add asset-side activity + reward columns.
-- All additive; metadata-only on PG 11+.

alter table app_activity_record_store
    -- Submitting app provider for the verdict. Nullable: unset pre-amendment.
    add column submitting_app_provider          text,
    -- Asset providers for which asset activity should be recorded.
    add column asset_activity_provider_parties  text[],
    -- Asset activity weight (bytes of traffic), one-to-one with
    -- asset_activity_provider_parties; must be NULL iff the parties array is NULL.
    add column asset_activity_weights           bigint[];

alter table app_activity_party_totals
    -- Per-party asset activity for the round, CC-denominated (bytes converted
    -- at aggregation time per the CIP-104 amendment).
    add column total_asset_activity_weight_cc  decimal(38,10) not null default 0,
    -- Number of activity records contributing asset activity for the party.
    add column num_asset_activity_records      bigint         not null default 0;

alter table app_activity_round_totals
    -- Number of parties with non-zero asset activity in the round.
    add column active_asset_provider_parties_count bigint not null default 0,
    -- Round-wide asset activity in bytes of traffic. Mirrors
    -- total_round_app_activity_weight.
    add column total_round_asset_activity_weight   bigint not null default 0;

alter table app_reward_party_totals
    -- Asset-side portion of the per-party minting allowance.
    -- MintingAllowance.amount hashes the sum (app + asset).
    add column total_asset_reward_amount decimal(38,10) not null default 0;

alter table app_reward_round_totals
    -- Asset-side issued CC in the round. Mirrors total_app_reward_minting_allowance.
    add column total_asset_reward_minting_allowance  decimal(38,10) not null default 0,
    -- Asset-side portion of CC burned by the CIP-104 combined threshold check.
    -- Mirrors total_app_reward_thresholded (which carries the app-side portion).
    add column total_asset_reward_thresholded        decimal(38,10) not null default 0,
    -- Number of parties receiving any asset reward in the round.
    add column rewarded_asset_provider_parties_count bigint         not null default 0,
    -- Round-wide asset activity in CC. Denominator of
    -- issuancePerFeaturedAssetBurnCC per the CIP-104 amendment.
    add column total_round_asset_activity_weight_cc  decimal(38,10) not null default 0;

-- No total_asset_reward_unclaimed column: under TBAR v1.1 only one round-wide
-- unclaimed exists (app's cascades to asset's budget), stored in the existing
-- total_app_reward_unclaimed column.
