-- Add participant_id column to dso_acs_store
alter table dso_acs_store
    add column participant_id text;

-- Create an index for fast lookups by participant_id
create index dso_acs_store_sid_mid_pn_tid_part_id
    on dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name, participant_id)
    where participant_id is not null;
