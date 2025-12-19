
### Implementation note

We expect Scan to maintain the following indices.
We expect Scan to use them in the following priority order:

```
-- Used when holder is specified.
create index idx_credentialrecord_holder ON CredentialRecord(holder, property, issuer, record_time);

-- Used when a property prefix is specified.
create index idx_credentialrecord_issuer ON CredentialRecord(property, issuer, record_time);

-- Used when issuer is specified.
create index idx_credentialrecord_issuer ON CredentialRecord(issuer, record_time);

-- Used when no filters are specified.
create index idx_credentialrecord_recordtime ON CredentialRecord(record_time);
```

The queries are constructed such that pagination follows the index order.
They use a deterministic paging order based on the index columns, where `record_time` and
`contract_id` are used as tie breakers.
An ascending order for record_time is chosen to implement first write wins
semantics, which is preferred as it incentivizes archiving older records.

The fallback order can be used to page through all records in the order in which
they were created; and it can also be used to tail the table to ingest all records as
they are created.

