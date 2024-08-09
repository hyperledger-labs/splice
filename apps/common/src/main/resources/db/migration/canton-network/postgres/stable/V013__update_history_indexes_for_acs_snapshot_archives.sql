-- efficient querying for ACS snapshots backfilling
create index archives on update_history_exercises (update_row_id) include (contract_id) where consuming;
