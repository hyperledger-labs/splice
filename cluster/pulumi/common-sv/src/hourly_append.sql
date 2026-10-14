BEGIN
  DECLARE current_watermark_micros INT64;
  DECLARE current_watermark_ts TIMESTAMP;
  DECLARE max_available_timestamp TIMESTAMP;
  DECLARE max_closed_timestamp TIMESTAMP;
  DECLARE records_processed INT64 DEFAULT 0;

 
  IF NOT EXISTS (
    SELECT 1 
    FROM {{prodInfoSchema}}
    WHERE table_name = '{{tableName}}'
  ) THEN
    CREATE TABLE {{prodTable}}
    PARTITION BY record_date AS 
    SELECT 
      *, 
      {{recordDateExpr}} AS record_date
    FROM {{stagingTable}} AS staging
    WHERE 1 = 0;
  END IF;


  CREATE TABLE IF NOT EXISTS {{watermarksTable}} (
    table_name STRING,
    last_watermark_time INT64
  );

CREATE TABLE IF NOT EXISTS {{prod_logs}} (
    table_name STRING,
    from_timestamp TIMESTAMP,
    to_timestamp TIMESTAMP,    
    record_count INT64,
    error_message STRING,
    logged_at TIMESTAMP,
    record_date DATE
  )
  PARTITION BY record_date
  CLUSTER BY table_name;

  --  Initialize watermark to epoch zero (0 micros) if missing
  IF NOT EXISTS (
    SELECT 1 
    FROM {{watermarksTable}} 
    WHERE table_name = '{{tableName}}'
  ) THEN
    INSERT INTO {{watermarksTable}} 
    VALUES ('{{tableName}}', 0);
  END IF;


  SET current_watermark_micros = (
    SELECT MAX(last_watermark_time) 
    FROM {{watermarksTable}} 
    WHERE table_name = '{{tableName}}'
  );

  SET current_watermark_ts = TIMESTAMP_MICROS(current_watermark_micros);

  --  Fetch max available timestamp from staging WITH PARTITION PRUNING
  SET max_available_timestamp = COALESCE(
    (
      SELECT MAX({{recordTimestampExpr}}) 
      FROM {{stagingTable}} AS staging
      WHERE (
          staging._PARTITIONTIME >= TIMESTAMP_SUB(current_watermark_ts, INTERVAL 3 HOUR)
          OR staging._PARTITIONTIME IS NULL
        )
    ),
    -- Fall back to current_watermark_ts if no new data arrived in the last 3 hours
    current_watermark_ts
  );

  -- Subtract 1 hour to safely close the ingestion window
  SET max_closed_timestamp = TIMESTAMP_SUB(max_available_timestamp, INTERVAL 1 HOUR);


  -- 6. Filter incremental data with Primary Key Deduplication & Partition Pruning
  CREATE TEMP TABLE temp_incremental AS (
    SELECT 
      staging.*, 
      {{recordDateExpr}} AS record_date
    FROM {{stagingTable}} AS staging
    WHERE (
        -- Cost control: Scan 3-hour partition window
        staging._PARTITIONTIME >= TIMESTAMP_SUB(current_watermark_ts, INTERVAL 3 HOUR)
        -- Streaming buffer protection for recent arrivals
        OR staging._PARTITIONTIME IS NULL
      )
      AND {{recordTimestampExpr}} > current_watermark_ts
      AND {{recordTimestampExpr}} <= max_closed_timestamp
    -- Primary Key Deduplication
    QUALIFY ROW_NUMBER() OVER (
      PARTITION BY {{primaryKeyExpr}}
      ORDER BY 
        staging.datastream_metadata.source_timestamp DESC,
        staging.datastream_metadata.change_sequence_number DESC
    ) = 1
  );
SET records_processed = (SELECT COUNT(1) FROM temp_incremental);

  IF records_processed > 0 THEN
    BEGIN TRANSACTION;
      INSERT INTO {{prodTable}}
      SELECT * FROM temp_incremental;

      UPDATE {{watermarksTable}}
      SET last_watermark_time = UNIX_MICROS(max_closed_timestamp)
      WHERE table_name = '{{tableName}}'
      AND last_watermark_time < UNIX_MICROS(max_closed_timestamp);

      INSERT INTO {{prod_logs}} (
        table_name,        
        from_timestamp,
        to_timestamp,
        record_count,
        error_message,
        logged_at,
        record_date
      ) VALUES (
        '{{tableName}}',
        TIMESTAMP_MICROS(current_watermark_micros),
        max_closed_timestamp,        
        records_processed,
        NULL,
        CURRENT_TIMESTAMP(),
        CURRENT_DATE()
      );

    COMMIT TRANSACTION;
    ELSE

    -- Record zero-count run when no new rows exist in the window
    INSERT INTO {{prod_logs}} (
      table_name,
      from_timestamp,
      to_timestamp,
      record_count,
      error_message,
      logged_at,
      record_date
    ) VALUES (
      '{{tableName}}',
      TIMESTAMP_MICROS(current_watermark_micros),
      max_closed_timestamp,
      0,
      NULL,
      CURRENT_TIMESTAMP(),
      CURRENT_DATE()
    );
  END IF;
EXCEPTION WHEN ERROR THEN
  -- Preserve original root-cause error message immediately
  SET pipeline_error = @@error.message;

  
  BEGIN
    ROLLBACK TRANSACTION;
  EXCEPTION WHEN ERROR THEN
    -- Suppress error if transaction was not open
  END;

  -- Persist failure record into prod_logs outside the rolled-back transaction
  BEGIN
    INSERT INTO {{prod_logs}} (
      table_name,
      from_timestamp,
      to_timestamp,     
      record_count,
      error_message,
      logged_at,
      record_date
    ) VALUES (
      '{{tableName}}',
      TIMESTAMP_MICROS(COALESCE(current_watermark_micros, 0)),
      COALESCE(max_closed_timestamp, TIMESTAMP_MICROS(0)),
      0,
      pipeline_error,
      CURRENT_TIMESTAMP(),
      CURRENT_DATE()
    );
  EXCEPTION WHEN ERROR THEN
    -- Prevent logging failure from masking the original pipeline exception
  END;

  -- Re-raise original root-cause error to orchestrator
  RAISE USING MESSAGE = pipeline_error;
END;