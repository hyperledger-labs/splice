alter table dso_acs_store
  add column provisional_featured_app_lock_for text;

create index dso_acs_store_provisional_featured_app_lock_for
    on dso_acs_store (store_id, migration_id, provisional_featured_app_lock_for)
    where provisional_featured_app_lock_for is not null;
