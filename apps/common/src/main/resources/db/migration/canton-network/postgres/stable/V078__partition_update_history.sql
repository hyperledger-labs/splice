-- Legacy tables will eventually be dropped by pruning.
-- Indexes are renamed to avoid clashes with the new tables.

alter table update_history_transactions
    rename to update_history_transactions_legacy;
alter sequence update_history_transactions_row_id_seq rename to update_history_transactions_legacy_row_id_seq;
alter table update_history_transactions_legacy
    rename constraint update_history_transactions_pkey to update_history_transactions_legacy_pkey;
alter index updt_hist_tran_hi_mi_di_rt rename to updt_hist_tran_hi_mi_di_rt_legacy;
alter index updt_hist_tran_hi_mi_rt_di rename to updt_hist_tran_hi_mi_rt_di_legacy;
alter index updt_hist_tran_hi_mi_ui_import_updates rename to updt_hist_tran_hi_mi_ui_import_updates_legacy;
alter index updt_hist_tran_hi_mi_ui_u rename to updt_hist_tran_hi_mi_ui_u_legacy;
alter index updt_hist_tran_hi_ui rename to updt_hist_tran_hi_ui_legacy;
-- Created at runtime by SqlIndexInitializationTrigger, might not exist yet
alter index if exists updt_hist_tran_hi_eth rename to updt_hist_tran_hi_eth_legacy;

alter table update_history_creates
    rename to update_history_creates_legacy;
alter sequence update_history_creates_v2_row_id_seq rename to update_history_creates_legacy_row_id_seq;
alter table update_history_creates_legacy
    rename constraint update_history_creates_pkey to update_history_creates_legacy_pkey;
alter index contract_create_lookup rename to contract_create_lookup_legacy;
alter index updt_hist_crea_hi_mi_rt rename to updt_hist_crea_hi_mi_rt_legacy;
alter index updt_hist_crea_hi_tidmn_tiden_pn_rid rename to updt_hist_crea_hi_tidmn_tiden_pn_rid_legacy;
alter index updt_hist_crea_unique rename to updt_hist_crea_unique_legacy;
-- Created at runtime by SqlIndexInitializationTrigger, might not exist yet
alter index if exists updt_hist_crea_hi_mi_ci_import_updates rename to updt_hist_crea_hi_mi_ci_import_updates_legacy;

alter table update_history_exercises
    rename to update_history_exercises_legacy;
alter sequence update_history_exercises_v2_row_id_seq rename to update_history_exercises_legacy_row_id_seq;
alter table update_history_exercises_legacy
    rename constraint update_history_exercises_pkey to update_history_exercises_legacy_pkey;
alter index updt_hist_exer_hi_mi_rt_co rename to updt_hist_exer_hi_mi_rt_co_legacy;
alter index updt_hist_exer_unique rename to updt_hist_exer_unique_legacy;

alter table update_history_assignments
    rename to update_history_assignments_legacy;
alter sequence update_history_assignments_row_id_seq rename to update_history_assignments_legacy_row_id_seq;
alter table update_history_assignments_legacy
    rename constraint update_history_assignments_pkey to update_history_assignments_legacy_pkey;
alter index updt_hist_assi_hi_mi_di_rt rename to updt_hist_assi_hi_mi_di_rt_legacy;
alter index updt_hist_assi_hi_mi_rt_di rename to updt_hist_assi_hi_mi_rt_di_legacy;
alter index updt_hist_assi_hi_mi_ui_u rename to updt_hist_assi_hi_mi_ui_u_legacy;

alter table update_history_unassignments
    rename to update_history_unassignments_legacy;
alter sequence update_history_unassignments_row_id_seq rename to update_history_unassignments_legacy_row_id_seq;
alter table update_history_unassignments_legacy
    rename constraint update_history_unassignments_pkey to update_history_unassignments_legacy_pkey;
alter index updt_hist_unas_hi_mi_di_rt rename to updt_hist_unas_hi_mi_di_rt_legacy;
alter index updt_hist_unas_hi_mi_rt_di rename to updt_hist_unas_hi_mi_rt_di_legacy;
alter index updt_hist_unas_hi_mi_ui_u rename to updt_hist_unas_hi_mi_ui_u_legacy;

-- TODO: figure out what happens to this
-- alter table update_history_backfilling
--    drop constraint update_history_backfilling_history_id_joining_migration_id_fkey;

-- Create all new tables:
-- * For sequences, ALTER SEQUENCE would fail with 'cannot change ownership of identity sequence'.
-- * The tables no longer have primary key, because it would have to be (row_id, record_time), which wouldn't prevent double inserts.
create table update_history_transactions
(
    like update_history_transactions_legacy including defaults including identity including storage including comments
)
    partition by range (record_time);
select setval(pg_get_serial_sequence('update_history_transactions', 'row_id'), last_value, is_called)
from update_history_transactions_legacy_row_id_seq;
alter table update_history_transactions
    add foreign key (history_id) references update_history_descriptors (id);
create index updt_hist_tran_hi_mi_di_rt on update_history_transactions (history_id, migration_id, domain_id, record_time);
create index updt_hist_tran_hi_mi_rt_di on update_history_transactions (history_id, migration_id, record_time, domain_id);
create index updt_hist_tran_hi_mi_ui_import_updates on update_history_transactions (history_id, migration_id, update_id)
    where record_time = -62135596800000000;
create index updt_hist_tran_hi_ui on update_history_transactions (history_id, update_id);
-- Same definition as in SqlIndexInitializationTrigger, which cannot create indexes concurrently on partitioned tables
create index updt_hist_tran_hi_eth on update_history_transactions (history_id, external_transaction_hash)
    where external_transaction_hash is not null;

create table update_history_creates
(
    like update_history_creates_legacy including defaults including identity including storage including comments
)
    partition by range (record_time);
select setval(pg_get_serial_sequence('update_history_creates', 'row_id'), last_value, is_called)
from update_history_creates_legacy_row_id_seq;
alter table update_history_creates
    add foreign key (history_id) references update_history_descriptors (id);
create index contract_create_lookup on update_history_creates (contract_id);
create index updt_hist_crea_hi_mi_rt on update_history_creates (history_id, migration_id, record_time);
create index updt_hist_crea_hi_tidmn_tiden_pn_rid on update_history_creates (history_id, template_id_module_name,
                                                                             template_id_entity_name, package_name,
                                                                             row_id);
create unique index updt_hist_crea_unique on update_history_creates (update_row_id, event_id, migration_id, record_time);
create index updt_hist_crea_hi_mi_ci_import_updates on update_history_creates (history_id, migration_id, contract_id)
    where record_time = -62135596800000000;

create table update_history_exercises
(
    like update_history_exercises_legacy including defaults including identity including storage including comments
)
    partition by range (record_time);
select setval(pg_get_serial_sequence('update_history_exercises', 'row_id'), last_value, is_called)
from update_history_exercises_legacy_row_id_seq;
alter table update_history_exercises
    add primary key (row_id, migration_id, record_time),
    add foreign key (history_id) references update_history_descriptors (id);
create index updt_hist_exer_hi_mi_rt_co on update_history_exercises (history_id, migration_id, record_time, consuming);
create unique index updt_hist_exer_unique on update_history_exercises (update_row_id, event_id, migration_id, record_time);

create table update_history_assignments
(
    like update_history_assignments_legacy including defaults including identity including storage including comments
)
    partition by range (record_time);
select setval(pg_get_serial_sequence('update_history_assignments', 'row_id'), last_value, is_called)
from update_history_assignments_legacy_row_id_seq;
alter table update_history_assignments
    add foreign key (history_id) references update_history_descriptors (id);
create index updt_hist_assi_hi_mi_di_rt on update_history_assignments (history_id, migration_id, domain_id, record_time);
create index updt_hist_assi_hi_mi_rt_di on update_history_assignments (history_id, migration_id, record_time, domain_id);
create unique index updt_hist_assi_hi_mi_ui_u on update_history_assignments (history_id, migration_id, domain_id, update_id, record_time);

create table update_history_unassignments
(
    like update_history_unassignments_legacy including defaults including identity including storage including comments
)
    partition by range (record_time);
select setval(pg_get_serial_sequence('update_history_unassignments', 'row_id'), last_value, is_called)
from update_history_unassignments_legacy_row_id_seq;
alter table update_history_unassignments
    add foreign key (history_id) references update_history_descriptors (id);
create index updt_hist_unas_hi_mi_di_rt on update_history_unassignments (history_id, migration_id, domain_id, record_time);
create index updt_hist_unas_hi_mi_rt_di on update_history_unassignments (history_id, migration_id, record_time, domain_id);
create unique index updt_hist_unas_hi_mi_ui_u on update_history_unassignments (history_id, migration_id, domain_id, update_id, record_time);

-- Initial partitions to be created by Scala code
