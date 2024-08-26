alter table acs_snapshot drop constraint acs_snapshot_pkey;
alter table acs_snapshot add primary key (history_id, migration_id, snapshot_record_time);
