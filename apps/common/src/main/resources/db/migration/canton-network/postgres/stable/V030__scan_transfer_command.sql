alter table scan_txlog_store
    add column transfer_command_contract_id text;
alter table scan_txlog_store
    add column transfer_command_sender text;
alter table scan_txlog_store
    add column transfer_command_nonce bigint;
create index scan_txlog_store_sid_tfer_cmd_sender_nonce
    on scan_txlog_store (store_id, transfer_command_sender, transfer_command_nonce)
    where transfer_command_sender is not null and transfer_command_nonce is not null;
