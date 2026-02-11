alter table user_wallet_txlog_store add column development_fund_coupon_contract_id text;

-- fast archival listing
create index user_wallet_txlog_store_archcoupon_sid_tli_et_en on user_wallet_txlog_store(store_id, tx_log_id, entry_type, entry_number desc) where tx_log_id='dev' and entry_type = 'fca';
-- fast create retrieval
create index user_wallet_txlog_store_createdcoupon_sid_tli_et_dccid on user_wallet_txlog_store(store_id, tx_log_id, entry_type, development_fund_coupon_contract_id) where tx_log_id='dev' and entry_type = 'fcc';
