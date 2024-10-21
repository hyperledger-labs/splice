-- Update scan_acs_store with validator from create_arguments
update scan_acs_store
set validator = create_arguments->>'validator'
where template_id_qualified_name = 'Splice.ValidatorLicense:ValidatorLicense'
  and create_arguments->>'validator' is not null;

create index scan_acs_store_sid_mid_tidqn_v on scan_acs_store(store_id, migration_id, template_id_qualified_name, validator) where validator is not null;
