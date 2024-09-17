-- Manual migration of the store descriptor for 'DbUserWalletStore'
-- to ensure the history tx log of past migrations is not lost due
-- to the change in the store descriptor.

WITH distinct_user_wallet_stores AS (
    -- If there are multiple stores for with the same descriptor after dropping the 'endUserName',
    -- then pick only one to convert
    SELECT DISTINCT ON (descriptor #- '{key, endUserName}') id
    FROM store_descriptors
    WHERE descriptor->>'name' = 'DbUserWalletStore'
      AND descriptor->>'version' = '1'
    ORDER BY (descriptor #- '{key, endUserName}'), id
)
UPDATE store_descriptors
SET descriptor = JSONB_SET(
            descriptor #- '{key,endUserName}', -- Removes the 'endUserName' field
            '{version}', '2' -- Updates the version to 2
                 )
FROM distinct_user_wallet_stores
WHERE store_descriptors.id = distinct_user_wallet_stores.id;


