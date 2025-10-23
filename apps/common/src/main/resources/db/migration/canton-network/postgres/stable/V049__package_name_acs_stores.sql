-- truncates are not strictly necessary, but lets us define the constraint as mandatory afterwards,
-- and also makes index creation faster since there's no data to index
truncate table acs_store_template cascade;
truncate table user_wallet_acs_store cascade;
truncate table external_party_wallet_acs_store cascade;
truncate table validator_acs_store cascade;
truncate table sv_acs_store cascade;
truncate table dso_acs_store cascade;
truncate table scan_acs_store cascade;
truncate table splitwell_acs_store cascade;

-- we just truncated the tables and there's no concurrent access while migrations are running,
-- so we can make the column not null.
-- All ACS store descriptors should be bumped in all stores to trigger reingestion.
alter table acs_store_template add column package_name text not null;
alter table user_wallet_acs_store add column package_name text not null;
alter table external_party_wallet_acs_store add column package_name text not null;
alter table validator_acs_store add column package_name text not null;
alter table sv_acs_store add column package_name text not null;
alter table dso_acs_store add column package_name text not null;
alter table scan_acs_store add column package_name text not null;
alter table splitwell_acs_store add column package_name text not null;

-- index creation should be fast since all ACS stores have been truncated

-- obtained with (unfortunately not all indexes are named consistently, so some require manual intervention) and IJ formatting:
-- select
--     'DROP INDEX ' || (regexp_matches(indexdef, 'CREATE INDEX (.*) ON'))[1] || ';' || E'\n'
--         'CREATE INDEX ' || replace((regexp_matches(indexdef, 'CREATE INDEX (.*) ON'))[1], 'tid', 'pn_tid') || ' ON ' || replace(replace(replace((regexp_matches(indexdef, 'CREATE INDEX (.*) ON (.*)'))[2], 'template_id_qualified_name', 'package_name, template_id_qualified_name'), 'public.', ''), 'USING btree', '') || ';' || E'\n'
-- from pg_indexes
-- where tablename like '%acs_store%'
--   and indexdef like '%template_id_qualified_name%';
DROP INDEX acs_store_template_sid_mid_tid_ce;
CREATE INDEX acs_store_template_sid_mid_pn_tid_ce ON acs_store_template (store_id, migration_id, package_name,
                                                                         template_id_qualified_name,
                                                                         contract_expires_at) WHERE (contract_expires_at IS NOT NULL);

DROP INDEX acs_store_template_sid_mid_tid_sn;
CREATE INDEX acs_store_template_sid_mid_pn_tid_sn ON acs_store_template (store_id, migration_id, package_name,
                                                                         template_id_qualified_name, state_number);

DROP INDEX user_wallet_acs_store_store_id_migration_id_template_id_qu_idx2;
CREATE INDEX user_wallet_acs_store_sid_mid_pn_tid_sn ON user_wallet_acs_store (store_id,
                                                                               migration_id,
                                                                               package_name,
                                                                               template_id_qualified_name,
                                                                               state_number);

DROP INDEX user_wallet_acs_store_sid_mid_tid_rcr;
CREATE INDEX user_wallet_acs_store_sid_mid_pn_tid_rcr ON user_wallet_acs_store (store_id, migration_id, package_name,
                                                                                template_id_qualified_name,
                                                                                reward_coupon_round) WHERE (reward_coupon_round IS NOT NULL);

DROP INDEX validator_acs_store_store_id_migration_id_template_id_qual_idx1;
CREATE INDEX validator_acs_store_sid_mid_pn_tid_exp ON validator_acs_store (store_id,
                                                                            migration_id,
                                                                            package_name,
                                                                            template_id_qualified_name,
                                                                            contract_expires_at) WHERE (contract_expires_at IS NOT NULL);

DROP INDEX validator_acs_store_store_id_migration_id_template_id_qual_idx2;
CREATE INDEX validator_acs_store_sid_mid_pn_tid_sn ON validator_acs_store (store_id,
                                                                           migration_id,
                                                                           package_name,
                                                                           template_id_qualified_name,
                                                                           state_number);

DROP INDEX validator_acs_store_sid_mid_tid_up;
CREATE INDEX validator_acs_store_sid_mid_pn_tid_up ON validator_acs_store (store_id, migration_id, package_name,
                                                                           template_id_qualified_name,
                                                                           user_party) WHERE (user_party IS NOT NULL);

DROP INDEX validator_acs_store_sid_mid_tid_un;
CREATE INDEX validator_acs_store_sid_mid_pn_tid_un ON validator_acs_store (store_id, migration_id, package_name,
                                                                           template_id_qualified_name,
                                                                           user_name) WHERE (user_name IS NOT NULL);

DROP INDEX validator_acs_store_sid_mid_tid_pp;
CREATE INDEX validator_acs_store_sid_mid_pn_tid_pp ON validator_acs_store (store_id, migration_id, package_name,
                                                                           template_id_qualified_name,
                                                                           provider_party) WHERE (provider_party IS NOT NULL);

DROP INDEX validator_acs_store_sid_mid_tid_vp;
CREATE INDEX validator_acs_store_sid_mid_pn_tid_vp ON validator_acs_store (store_id, migration_id, package_name,
                                                                           template_id_qualified_name,
                                                                           validator_party) WHERE (validator_party IS NOT NULL);

DROP INDEX validator_acs_store_sid_mid_tid_tdi;
CREATE INDEX validator_acs_store_sid_mid_pn_tid_tdi ON validator_acs_store (store_id, migration_id, package_name,
                                                                            template_id_qualified_name,
                                                                            traffic_domain_id) WHERE (traffic_domain_id IS NOT NULL);

DROP INDEX sv_acs_store_sid_mid_tid_vocs;
CREATE INDEX sv_acs_store_sid_mid_pn_tid_vocs ON sv_acs_store (store_id, migration_id, package_name,
                                                               template_id_qualified_name,
                                                               onboarding_secret) WHERE (onboarding_secret IS NOT NULL);

DROP INDEX sv_acs_store_sid_mid_tid_asicn;
CREATE INDEX sv_acs_store_sid_mid_pn_tid_asicn ON sv_acs_store (store_id, migration_id, package_name,
                                                                template_id_qualified_name,
                                                                sv_candidate_name) WHERE (sv_candidate_name IS NOT NULL);

DROP INDEX acs_store_template_sid_mid_tid_en;
CREATE INDEX acs_store_template_sid_mid_pn_tid_en ON acs_store_template (store_id, migration_id, package_name,
                                                                         template_id_qualified_name, event_number);

DROP INDEX validator_acs_store_store_id_migration_id_template_id_quali_idx;
CREATE INDEX validator_acs_store_sid_mid_pn_tid_en ON validator_acs_store (store_id,
                                                                           migration_id,
                                                                           package_name,
                                                                           template_id_qualified_name,
                                                                           event_number);

DROP INDEX user_wallet_acs_store_store_id_migration_id_template_id_qua_idx;
CREATE INDEX user_wallet_acs_store_sid_mid_pn_tid_en ON user_wallet_acs_store (store_id,
                                                                               migration_id,
                                                                               package_name,
                                                                               template_id_qualified_name,
                                                                               event_number);

DROP INDEX user_wallet_acs_store_store_id_migration_id_template_id_qu_idx1;
CREATE INDEX user_wallet_acs_store_sid_mid_pn_tid_exp ON user_wallet_acs_store (store_id,
                                                                                migration_id,
                                                                                package_name,
                                                                                template_id_qualified_name,
                                                                                contract_expires_at) WHERE (contract_expires_at IS NOT NULL);

DROP INDEX dso_acs_store_store_id_migration_id_template_id_qualified_n_idx;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_en ON dso_acs_store (store_id, migration_id,
                                                               package_name,
                                                               template_id_qualified_name,
                                                               event_number);

DROP INDEX dso_acs_store_store_id_migration_id_template_id_qualified__idx1;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_exp ON dso_acs_store (store_id, migration_id,
                                                                package_name,
                                                                template_id_qualified_name,
                                                                contract_expires_at) WHERE (contract_expires_at IS NOT NULL);

DROP INDEX sv_acs_store_store_id_migration_id_template_id_qualified_na_idx;
CREATE INDEX sv_acs_store_sid_mid_pn_tid_en ON sv_acs_store (store_id, migration_id,
                                                             package_name,
                                                             template_id_qualified_name,
                                                             event_number);

DROP INDEX sv_acs_store_store_id_migration_id_template_id_qualified_n_idx1;
CREATE INDEX sv_acs_store_sid_mid_pn_tid_exp ON sv_acs_store (store_id, migration_id,
                                                              package_name,
                                                              template_id_qualified_name,
                                                              contract_expires_at) WHERE (contract_expires_at IS NOT NULL);

DROP INDEX sv_acs_store_store_id_migration_id_template_id_qualified_n_idx2;
CREATE INDEX sv_acs_store_sid_mid_pn_tid_sn ON sv_acs_store (store_id, migration_id,
                                                             package_name,
                                                             template_id_qualified_name,
                                                             state_number);

DROP INDEX dso_acs_store_store_id_migration_id_template_id_qualified__idx2;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_sn ON dso_acs_store (store_id, migration_id,
                                                               package_name,
                                                               template_id_qualified_name,
                                                               state_number);

DROP INDEX dso_acs_store_sid_tid_mr;
CREATE INDEX dso_acs_store_sid_pn_tid_mr ON dso_acs_store (store_id, migration_id, package_name,
                                                           template_id_qualified_name,
                                                           mining_round) WHERE (mining_round IS NOT NULL);

DROP INDEX dso_acs_store_sid_tid_tcid;
CREATE INDEX dso_acs_store_sid_pn_tid_tcid ON dso_acs_store (store_id, migration_id, package_name,
                                                             template_id_qualified_name,
                                                             vote_request_tracking_cid) WHERE (vote_request_tracking_cid IS NOT NULL);

DROP INDEX dso_acs_store_sid_tid_croe;
CREATE INDEX dso_acs_store_sid_pn_tid_croe ON dso_acs_store (store_id, migration_id, package_name,
                                                             template_id_qualified_name,
                                                             amulet_round_of_expiry) WHERE (amulet_round_of_expiry IS NOT NULL);

DROP INDEX dso_acs_store_sid_tid_ah_c;
CREATE INDEX dso_acs_store_sid_pn_tid_ah_c ON dso_acs_store (store_id, migration_id, package_name,
                                                             template_id_qualified_name, action_requiring_confirmation,
                                                             confirmer) WHERE (action_requiring_confirmation IS NOT NULL);

DROP INDEX dso_acs_store_coupons;
CREATE INDEX dso_acs_store_coupons ON dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name,
                                                     reward_round, reward_party,
                                                     app_reward_is_featured) WHERE ((reward_round IS NOT NULL) AND (reward_party IS NOT NULL));

DROP INDEX dso_acs_store_sid_tid_sot;
CREATE INDEX dso_acs_store_sid_pn_tid_sot ON dso_acs_store (store_id, migration_id, package_name,
                                                            template_id_qualified_name,
                                                            sv_onboarding_token) WHERE (sv_onboarding_token IS NOT NULL);

DROP INDEX dso_acs_store_sid_mid_tid_scp_scn;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_scp_scn ON dso_acs_store (store_id, migration_id, package_name,
                                                                    template_id_qualified_name, sv_candidate_party,
                                                                    sv_candidate_name) WHERE (sv_candidate_party IS NOT NULL);

DROP INDEX dso_acs_store_sid_mid_tid_scn;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_scn ON dso_acs_store (store_id, migration_id, package_name,
                                                                template_id_qualified_name,
                                                                sv_candidate_name) WHERE (sv_candidate_name IS NOT NULL);

DROP INDEX dso_acs_store_sid_mid_tid_v_tp;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_v_tp ON dso_acs_store (store_id, migration_id, package_name,
                                                                 template_id_qualified_name, validator,
                                                                 total_traffic_purchased) WHERE (validator IS NOT NULL);

DROP INDEX dso_acs_store_sid_mid_tid_ah_r;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_ah_r ON dso_acs_store (store_id, migration_id, package_name,
                                                                 template_id_qualified_name,
                                                                 action_requiring_confirmation,
                                                                 requester) WHERE (action_requiring_confirmation IS NOT NULL);

DROP INDEX dso_acs_store_sid_mid_tid_ere_r;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_ere_r ON dso_acs_store (store_id, migration_id, package_name,
                                                                  template_id_qualified_name, election_request_epoch,
                                                                  requester) WHERE ((election_request_epoch IS NOT NULL) AND (requester IS NOT NULL));

DROP INDEX dso_acs_store_sid_mid_tid_acecc;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_acecc ON dso_acs_store (store_id, migration_id, package_name,
                                                                  template_id_qualified_name,
                                                                  action_ans_entry_context_cid) WHERE (action_ans_entry_context_cid IS NOT NULL);

DROP INDEX dso_acs_store_sid_mid_tid_sccid;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_sccid ON dso_acs_store (store_id, migration_id, package_name,
                                                                  template_id_qualified_name,
                                                                  subscription_reference_contract_id) WHERE (subscription_reference_contract_id IS NOT NULL);

DROP INDEX dso_acs_store_sid_mid_tid_snpd;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_snpd ON dso_acs_store (store_id, migration_id, package_name,
                                                                 template_id_qualified_name,
                                                                 subscription_next_payment_due_at) WHERE (subscription_next_payment_due_at IS NOT NULL);

DROP INDEX scan_acs_store_store_id_migration_id_template_id_qualified__idx;
CREATE INDEX scan_acs_store_sid_mid_pn_tid_en ON scan_acs_store (store_id, migration_id,
                                                                 package_name,
                                                                 template_id_qualified_name,
                                                                 event_number);

DROP INDEX scan_acs_store_store_id_migration_id_template_id_qualified_idx1;
CREATE INDEX scan_acs_store_sid_mid_pn_tid_exp ON scan_acs_store (store_id, migration_id,
                                                                  package_name,
                                                                  template_id_qualified_name,
                                                                  contract_expires_at) WHERE (contract_expires_at IS NOT NULL);

DROP INDEX scan_acs_store_store_id_migration_id_template_id_qualified_idx2;
CREATE INDEX scan_acs_store_sid_mid_pn_tid_sn ON scan_acs_store (store_id, migration_id,
                                                                 package_name,
                                                                 template_id_qualified_name,
                                                                 state_number);

DROP INDEX scan_acs_store_sid_mid_tid_val;
CREATE INDEX scan_acs_store_sid_mid_pn_tid_val ON scan_acs_store (store_id, migration_id, package_name,
                                                                  template_id_qualified_name, validator,
                                                                  event_number) WHERE (validator IS NOT NULL);

DROP INDEX scan_acs_store_sid_mid_tid_farp;
CREATE INDEX scan_acs_store_sid_mid_pn_tid_farp ON scan_acs_store (store_id, migration_id, package_name,
                                                                   template_id_qualified_name,
                                                                   featured_app_right_provider) WHERE (featured_app_right_provider IS NOT NULL);

DROP INDEX scan_acs_store_sid_mid_tid_vlrc;
CREATE INDEX scan_acs_store_sid_mid_pn_tid_vlrc ON scan_acs_store (store_id, migration_id, package_name,
                                                                   template_id_qualified_name,
                                                                   validator_license_rounds_collected) WHERE (validator_license_rounds_collected IS NOT NULL);

DROP INDEX scan_acs_store_sid_mid_tid_mtm_mtd;
CREATE INDEX scan_acs_store_sid_mid_pn_tid_mtm_mtd ON scan_acs_store (store_id, migration_id, package_name,
                                                                      template_id_qualified_name, member_traffic_member,
                                                                      member_traffic_domain) WHERE (member_traffic_member IS NOT NULL);

DROP INDEX scan_acs_store_wallet_party_idx;
CREATE INDEX scan_acs_store_wallet_party_idx ON scan_acs_store (store_id, migration_id, package_name,
                                                                template_id_qualified_name,
                                                                wallet_party) WHERE (wallet_party IS NOT NULL);

DROP INDEX splitwell_acs_store_store_id_migration_id_template_id_quali_idx;
CREATE INDEX splitwell_acs_store_sid_mid_pn_tid_en ON splitwell_acs_store (store_id,
                                                                           migration_id,
                                                                           package_name,
                                                                           template_id_qualified_name,
                                                                           event_number);

DROP INDEX splitwell_acs_store_store_id_migration_id_template_id_qual_idx1;
CREATE INDEX splitwell_acs_store_sid_mid_pn_tid_exp ON splitwell_acs_store (store_id,
                                                                            migration_id,
                                                                            package_name,
                                                                            template_id_qualified_name,
                                                                            contract_expires_at) WHERE (contract_expires_at IS NOT NULL);

DROP INDEX splitwell_acs_store_store_id_migration_id_template_id_qual_idx2;
CREATE INDEX splitwell_acs_store_sid_mid_pn_tid_sn ON splitwell_acs_store (store_id,
                                                                           migration_id,
                                                                           package_name,
                                                                           template_id_qualified_name,
                                                                           state_number);

DROP INDEX dso_acs_store_sid_mid_tid_cen_exp;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_cen_exp ON dso_acs_store (store_id, migration_id, package_name,
                                                                    template_id_qualified_name, ans_entry_name,
                                                                    contract_expires_at) WHERE ((ans_entry_name IS NOT NULL) AND (contract_expires_at IS NOT NULL));

DROP INDEX scan_acs_store_sid_mid_den_tpo_exp;
CREATE INDEX scan_acs_store_sid_mid_pn_tid_aen_tpo_exp ON scan_acs_store (store_id, migration_id, package_name,
                                                                          template_id_qualified_name, ans_entry_name
                                                                          text_pattern_ops,
                                                                          contract_expires_at) WHERE ((ans_entry_name IS NOT NULL) AND (contract_expires_at IS NOT NULL));

DROP INDEX scan_acs_store_sid_mid_deo_den_exp;
CREATE INDEX scan_acs_store_sid_mid_deo_den_exp ON scan_acs_store (store_id, migration_id, package_name,
                                                                   template_id_qualified_name, ans_entry_owner,
                                                                   ans_entry_name, contract_expires_at) WHERE (
    (ans_entry_owner IS NOT NULL) AND (ans_entry_name IS NOT NULL) AND (contract_expires_at IS NOT NULL));

DROP INDEX scan_acs_store_sid_mid_tidqn_v;
CREATE INDEX scan_acs_store_sid_mid_pn_tid_v ON scan_acs_store (store_id, migration_id, package_name,
                                                                template_id_qualified_name,
                                                                validator) WHERE (validator IS NOT NULL);

DROP INDEX user_wallet_acs_store_sid_mid_tid_rcv;
CREATE INDEX user_wallet_acs_store_sid_mid_pn_tid_rcv ON user_wallet_acs_store (store_id, migration_id, package_name,
                                                                                template_id_qualified_name,
                                                                                transfer_preapproval_receiver) WHERE (transfer_preapproval_receiver IS NOT NULL);

DROP INDEX external_party_wallet_acs_sto_store_id_migration_id_templat_idx;
CREATE INDEX external_party_wallet_acs_store_sid_mid_pn_tid_en ON external_party_wallet_acs_store (store_id,
                                                                                                   migration_id,
                                                                                                   package_name,
                                                                                                   template_id_qualified_name,
                                                                                                   event_number);

DROP INDEX external_party_wallet_acs_sto_store_id_migration_id_templa_idx1;
CREATE INDEX external_party_wallet_acs_sid_mid_pn_tid_exp ON external_party_wallet_acs_store (store_id,
                                                                                              migration_id,
                                                                                              package_name,
                                                                                              template_id_qualified_name,
                                                                                              contract_expires_at) WHERE (contract_expires_at IS NOT NULL);

DROP INDEX external_party_wallet_acs_sto_store_id_migration_id_templa_idx2;
CREATE INDEX external_party_wallet_store_sid_mid_pn_tid_sn ON external_party_wallet_acs_store (store_id,
                                                                                               migration_id,
                                                                                               package_name,
                                                                                               template_id_qualified_name,
                                                                                               state_number);

DROP INDEX external_party_wallet_acs_store_sid_mid_tid_rcr;
CREATE INDEX external_party_wallet_acs_store_sid_mid_pn_tid_rcr ON external_party_wallet_acs_store (store_id,
                                                                                                    migration_id,
                                                                                                    package_name,
                                                                                                    template_id_qualified_name,
                                                                                                    reward_coupon_round) WHERE (reward_coupon_round IS NOT NULL);

DROP INDEX dso_acs_store_wallet_party_idx;
CREATE INDEX dso_acs_store_wallet_party_idx ON dso_acs_store (store_id, migration_id, package_name,
                                                              template_id_qualified_name,
                                                              wallet_party) WHERE (wallet_party IS NOT NULL);

DROP INDEX dso_acs_store_sid_mid_tid_mtm_mtd;
CREATE INDEX dso_acs_store_sid_mid_pn_tid_mtm_mtd ON dso_acs_store (store_id, migration_id, package_name,
                                                                    template_id_qualified_name, member_traffic_member,
                                                                    member_traffic_domain) WHERE (member_traffic_member IS NOT NULL);

DROP INDEX dso_acs_contract_id_hash_idx;
CREATE INDEX dso_acs_contract_id_hash_idx on dso_acs_store (store_id, migration_id, stable_int32_hash(contract_id))
  where
    package_name = 'splice-amulet' and
    template_id_qualified_name = 'Splice.Amulet:FeaturedAppActivityMarker';
