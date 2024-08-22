-- no production databases contain any data in these tables at the time this migration will run.
truncate table acs_snapshot cascade;
truncate table acs_snapshot_data cascade;

alter table acs_snapshot add column history_id bigint not null;
alter table acs_snapshot drop constraint acs_snapshot_pkey;
alter table acs_snapshot add primary key (history_id, snapshot_record_time);
