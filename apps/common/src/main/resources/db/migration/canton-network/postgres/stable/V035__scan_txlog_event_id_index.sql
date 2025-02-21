DROP INDEX scan_txlog_store_store_id_entry_type_idx; -- covered by the one below

CREATE INDEX scan_txlog_store_sid_et_ei ON scan_txlog_store(store_id, entry_type, event_id);
