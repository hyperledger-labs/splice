-- V065: plpgsql functions for Merkle tree hash computation over app reward batches.
-- These replicate the Daml CryptoHash module (Splice.Amulet.CryptoHash)
-- All hash functions return 64-char hex strings (text), matching Daml's Hash type.
-- Callers that store results in bytea columns (batch_hash, root_hash) must
-- convert with decode(result, 'hex').

-- ============================================================================
-- Primitive hash functions matching Daml CryptoHash encoding
-- ============================================================================

-- Hash a text scalar: sha256(value) → 64-char hex string.
-- Matches: instance Hashable Text where hash = hashText . id
CREATE FUNCTION daml_crypto_hash_text(s text) RETURNS text
  RETURNS NULL ON NULL INPUT
  AS $$ SELECT encode(extensions.digest(s, 'sha256'), 'hex') $$
  LANGUAGE sql IMMUTABLE PARALLEL SAFE;

-- Hash a list of already-hashed elements.
-- Matches: hashListInternal ts = sha256(intercalate "|" (show(length ts) :: ts))
CREATE FUNCTION daml_crypto_hash_list(elems text[]) RETURNS text
  RETURNS NULL ON NULL INPUT
  AS $$
    SELECT encode(extensions.digest(
      array_to_string(
        ARRAY[cardinality(elems)::text] || elems,
        '|'
      ),
      'sha256'
    ), 'hex')
  $$ LANGUAGE sql IMMUTABLE PARALLEL SAFE;

-- Hash a variant with a tag and field hashes.
-- Matches: hashVariant tag fields = sha256(intercalate "|" (tag :: show(length fields) :: map (.value) fields))
CREATE FUNCTION daml_crypto_hash_variant(tag text, fields text[]) RETURNS text
  RETURNS NULL ON NULL INPUT
  AS $$
    SELECT encode(extensions.digest(
      array_to_string(
        ARRAY[tag, cardinality(fields)::text] || fields,
        '|'
      ),
      'sha256'
    ), 'hex')
  $$ LANGUAGE sql IMMUTABLE PARALLEL SAFE;


-- ============================================================================
-- Domain-specific hash functions for reward accounting
-- ============================================================================

-- Hash a single MintingAllowance record.
-- Matches: hash MintingAllowance{provider, amount} = hashRecord [hash provider, hash amount]
CREATE FUNCTION hash_minting_allowance(provider text, amount text) RETURNS text
  RETURNS NULL ON NULL INPUT
  AS $$ SELECT daml_crypto_hash_list(ARRAY[daml_crypto_hash_text(provider), daml_crypto_hash_text(amount)]) $$
  LANGUAGE sql IMMUTABLE PARALLEL SAFE;

-- Hash a BatchOfMintingAllowances variant.
-- Matches: hash (BatchOfMintingAllowances allowances) = hashVariant "BatchOfMintingAllowances" [hash allowances]
-- where hash allowances = hashList (map hash allowances)
CREATE FUNCTION hash_batch_of_minting_allowances(allowance_hashes text[]) RETURNS text
  RETURNS NULL ON NULL INPUT
  AS $$ SELECT daml_crypto_hash_variant('BatchOfMintingAllowances', ARRAY[daml_crypto_hash_list(allowance_hashes)]) $$
  LANGUAGE sql IMMUTABLE PARALLEL SAFE;

-- Hash a BatchOfBatches variant.
-- Matches: hash (BatchOfBatches batchHashes) = hashVariant "BatchOfBatches" [hash batchHashes]
-- where hash batchHashes = hashList (map hash batchHashes)  (identity on Hash)
CREATE FUNCTION hash_batch_of_batches(child_hashes text[]) RETURNS text
  RETURNS NULL ON NULL INPUT
  AS $$ SELECT daml_crypto_hash_variant('BatchOfBatches', ARRAY[daml_crypto_hash_list(child_hashes)]) $$
  LANGUAGE sql IMMUTABLE PARALLEL SAFE;
