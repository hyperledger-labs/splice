-- if the only store descriptors belong to the SV app or Splitwell, that means we can truncate the update_history tables

DO $$
DECLARE
    descriptors TEXT[];
BEGIN

-- array equality (ordered) ensures that exactly the provided descriptors, no more, no less, are there <=> it's the SV app or splitwell
select array_agg(store_name order by store_name) into descriptors
from update_history_descriptors;

IF (descriptors  = '{"DbSvDsoStore", "DbSvSvStore"}' OR descriptors = '{"DbSplitwellStore"}') THEN
    RAISE NOTICE 'Truncating update history tables as only SV/Splitwell app descriptors are present. Descriptors: %', descriptors::text;
    EXECUTE 'TRUNCATE TABLE update_history_assignments CASCADE';
    EXECUTE 'TRUNCATE TABLE update_history_unassignments CASCADE';
    EXECUTE 'TRUNCATE TABLE update_history_backfilling CASCADE';
    EXECUTE 'TRUNCATE TABLE update_history_creates CASCADE';
    EXECUTE 'TRUNCATE TABLE update_history_exercises CASCADE';
    EXECUTE 'TRUNCATE TABLE update_history_transactions CASCADE';
    EXECUTE 'TRUNCATE TABLE update_history_last_ingested_offsets CASCADE';
    EXECUTE 'TRUNCATE TABLE update_history_descriptors CASCADE';
ELSE
    RAISE NOTICE 'This is not the SV or Splitwell app, NOT truncating update history tables. Descriptors: %', descriptors::text;
END IF;

END $$;
