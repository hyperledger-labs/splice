-- Format a numeric so that its text representation matches Daml's
-- `show (d : Numeric n)`, which calls `Numeric.toUnscaledString`:
--   * strip trailing zeros from the fractional part
--   * drop a bare trailing "."
--   * if there is no "." after stripping, append ".0"
-- Examples (for `numeric(38,10)` input):
--   "10.0000000000"      -> "10.0"
--   "23759.7510000000"   -> "23759.751"
--   "0.0000000000"       -> "0.0"
-- Scan stores amounts as `decimal(38,10)` whose `::text` cast preserves all
-- trailing zeros; Daml's `show` strips them. Hash inputs must match exactly,
-- so use this helper whenever hashing a numeric value.
CREATE FUNCTION daml_numeric_to_text(x numeric) RETURNS text
  RETURNS NULL ON NULL INPUT
  AS $$
    SELECT CASE
      WHEN stripped LIKE '%.%' THEN stripped
      ELSE stripped || '.0'
    END
    FROM (
      SELECT regexp_replace(
        regexp_replace(x::text, '0+$', ''),  -- strip trailing zeros
        '\.$', ''                             -- then drop any bare trailing "."
      ) AS stripped
    ) s
  $$ LANGUAGE sql IMMUTABLE PARALLEL SAFE;
