alter table scan_txlog_store
    add column transfer_command_contract_id text;
create index scan_txlog_store_sid_tfer_cmd
    on scan_txlog_store (store_id, transfer_command_contract_id)
    where transfer_command_contract_id is not null;
