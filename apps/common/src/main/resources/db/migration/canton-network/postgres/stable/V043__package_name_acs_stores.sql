truncate table acs_store_template;
truncate table user_wallet_acs_store;
truncate table external_party_wallet_acs_store;
truncate table validator_acs_store;
truncate table sv_acs_store;
truncate table dso_acs_store;
truncate table scan_acs_store;
truncate table splitwell_acs_store;

-- we just truncated the tables and there's no concurrent access while migrations are running,
-- so we can make the column not null.
-- All ACS store descriptors should be bumped in all stores to trigger reingestion.
alter table acs_store_template add column package_name text not null;
alter table user_wallet_acs_store add column package_name text not null;
alter table external_party_wallet_acs_store add column package_name text not null;
alter table validator_acs_store add column package_name text not null;
alter table sv_acs_store add column package_name text not null;
alter table dso_acs_store add column package_name text not null;
alter table scan_acs_store add column package_name text not null;
alter table splitwell_acs_store add column package_name text not null;

-- TODO: add indexes including the package_name column, and drop all those that don't have it
