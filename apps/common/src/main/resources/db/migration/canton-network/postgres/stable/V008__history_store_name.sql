-- Add the new column
ALTER TABLE update_history_descriptors ADD COLUMN store_name text;

-- Declare a variable to hold the constraint name
DO $$
DECLARE
constraint_name TEXT;
BEGIN
    -- Query to get the constraint name
SELECT conname INTO constraint_name
FROM pg_constraint
WHERE conrelid = 'update_history_descriptors'::regclass
    AND conkey = ARRAY(SELECT attnum FROM pg_attribute WHERE attrelid = 'update_history_descriptors'::regclass AND attname IN ('party', 'participant_id') order by attnum asc);

-- If the constraint exists, drop it
IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE update_history_descriptors DROP CONSTRAINT %I', constraint_name);
END IF;

    -- Add the new unique constraint
ALTER TABLE update_history_descriptors ADD CONSTRAINT unique_party_participant_id_store_name UNIQUE (party, participant_id, store_name);
END $$;
