alter table scan_verdict_store
    -- Adds a nullable round_number column so the addition does not require rewriting the whole table
    -- Note that this is denormalized into the `app_activity_record_store.round_number` field to efficiently compute
    -- per-round totals of activities.
    -- Make sure that the values agree when setting both of them.
    add column round_number          bigint null;
