alter table dso_acs_store
  add column conversion_rate_feed_publisher text;
create index dso_acs_store_sid_mid_tid_pub
    on dso_acs_store (store_id, migration_id, template_id_qualified_name, conversion_rate_feed_publisher, event_number desc)
    where conversion_rate_feed_publisher is not null;
