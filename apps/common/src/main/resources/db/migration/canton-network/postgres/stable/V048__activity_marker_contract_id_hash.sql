-- Int32 hash that is stable across Postgres versions.
-- Note: we do not need cryptographic security, and thus md5 is fine.
CREATE FUNCTION stable_int32_hash(input text)
  RETURNS int AS $$
      SELECT ('x' || substr(md5(input), 1, 8))::bit(32)::int;
  $$ LANGUAGE sql IMMUTABLE;


-- An expression index to make it easy to randomly select a batch of N featured app activity markers to convert
-- by picking a random int32 and selecting the next N app activity markers.
create index dso_acs_contract_id_hash_idx on dso_acs_store (store_id, migration_id, stable_int32_hash(contract_id))
  where template_id_qualified_name = 'Splice.Amulet:FeaturedAppActivityMarker';


