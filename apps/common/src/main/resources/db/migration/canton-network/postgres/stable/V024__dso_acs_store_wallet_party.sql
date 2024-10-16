-- Column for a party that has a CC wallet. Used in different contracts
-- e.g. for the sender of a TransferCommandCounter.
alter table dso_acs_store
  add column wallet_party text;
create index dso_acs_store_wallet_party_idx
  on dso_acs_store (store_id, migration_id, template_id_qualified_name, wallet_party)
  where wallet_party is not null;
