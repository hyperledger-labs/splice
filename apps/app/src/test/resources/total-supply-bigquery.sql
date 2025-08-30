-- TODO (DACH-NY/canton-network-internal#362) put this file somewhere that makes sense
DECLARE
  as_of_record_time timestamp;
DECLARE
  migration_id int64;
DECLARE
  locked,
  unlocked,
  unminted,
  minted,
  current_supply_total,
  allowed_mint,
  burned bignumeric;

SET as_of_record_time = iso_timestamp('2025-08-06T00:00:00Z');
SET migration_id = 3;
SET locked = locked(as_of_record_time, migration_id);
SET unlocked = unlocked(as_of_record_time, migration_id);
SET unminted = unminted(as_of_record_time, migration_id);
SET minted = minted(as_of_record_time, migration_id);
SET burned = burned(as_of_record_time, migration_id);
SET current_supply_total = locked + unlocked;
SET allowed_mint = unminted + minted;
SELECT
  locked locked,
  unlocked unlocked,
  current_supply_total current_supply_total,
  unminted unminted,
  minted minted,
  allowed_mint allowed_mint,
  burned burned;
