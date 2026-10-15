-- The synchronizer id from a RegisteredSynchronizer, so Scan can serve a registration by
-- synchronizer id without a JSON extraction on every candidate row.
alter table scan_acs_store
  add column registered_synchronizer_id text;
