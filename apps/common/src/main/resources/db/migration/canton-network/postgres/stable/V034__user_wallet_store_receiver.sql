alter table user_wallet_acs_store
  add column transfer_preapproval_receiver text;
create index user_wallet_acs_store_sid_mid_tid_rcv
    on user_wallet_acs_store (store_id, migration_id,  template_id_qualified_name, transfer_preapproval_receiver)
    where transfer_preapproval_receiver is not null;
update user_wallet_acs_store
   set transfer_preapproval_receiver = create_arguments->>'receiver'
 where template_id_qualified_name = 'Splice.AmuletRules:TransferPreapproval'
    or template_id_qualified_name = 'Splice.Wallet.TransferPreapproval:TransferPreapprovalProposal';
