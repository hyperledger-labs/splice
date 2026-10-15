-- The synchronizer id from a RegisteredSynchronizer, so the SV app can serve a registration by
-- synchronizer id from its DSO store without a JSON extraction on every candidate row.
alter table dso_acs_store
  add column registered_synchronizer_id text;
