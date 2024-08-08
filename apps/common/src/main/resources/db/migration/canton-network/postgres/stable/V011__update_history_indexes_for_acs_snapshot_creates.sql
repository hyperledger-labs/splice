-- cleanup
-- this is redundant with updt_hist_exer_unique
drop index updt_hist_exer_uri;
-- this is redundant with updt_hist_crea_unique
drop index updt_hist_crea_uri;

-- efficient querying for ACS snapshots backfilling
create index contract_create_lookup on update_history_creates (contract_id);
