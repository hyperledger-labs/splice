-- this is necessary to avoid postgres costly maintenance of foreign keys during delete operation in update_history tables
create index acs_snapshot_data_cid on acs_snapshot_data (create_id);
