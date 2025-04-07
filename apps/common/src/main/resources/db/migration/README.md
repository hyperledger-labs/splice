# DB migrations

This document goes over how to apply changes to the database schema and/or the [stores themselves](/apps/common/src/main/scala/org/lfdecentralizedtrust/splice/store/AppStore.scala).

## Required knowledge

- [Flyway](https://documentation.red-gate.com/fd/quickstart-how-flyway-works-184127223.html)
- [SQL (specifically, PostgreSQL's dialect)](https://www.postgresql.org/docs/current/sql-createtable.html)

## Flyway usage in Splice apps

- Once a Flyway migration script is included in a released version (more precisely, once you can't rule out that it's applied in _any_ production deployment),
  the script must never be modified again. This includes modifying comments - the checksum of the file content must forever remain constant.
  - At the time of writing, we are still resetting the DevNet/TestNet cluster state with each software release,
    so our single Flyway script (`V001__create_schema.sql`) can still be edited in-place.
  - If you realize you made a mistake in a deployed script, add another script that fixes the mistake.
  - In exceptional cases, you may need to modify an already deployed script.
    Like `git rebase -i`, Flyway has advanced commands that can be used to modify existing history, but those should be used with great care.

## Changing the indexes

For performance reasons, you might need to create, update or remove an index on the database.
This is straightforward:
- Create a new migration file `VXXX__a_title.sql` in [canton-network/postgres/stable](canton-network%2Fpostgres%2Fstable).
  The numbering is incremental and the title should be descriptive.
- Add your changes to the file (`CREATE/DROP INDEX...`).
- The migration will be applied next time the application is started if it wasn't applied yet.

For example:
```postgresql
-- V042_index_lookup_featuredappright.sql

-- lookup FeaturedAppRight
create index scan_acs_store_sid_tid_farp
    on scan_acs_store (store_id, template_id_qualified_name, featured_app_right_provider)
    where featured_app_right_provider is not null;
```

Note:
- The name of the index is the name of the table followed by the initials of the columns it indexes:
  - store_id => sid
  - template_id_qualified_name => tid
  - featured_app_right_provider => farp
- The index is [partial](https://www.postgresql.org/docs/current/indexes-partial.html), that is, it specifies a `where` clause.
  This avoids putting unnecessary entries with `NULL` values in the index, thus lowering storage costs.

## Adding a new column

You might for example need to implement a new query to satisfy the requirements of a new endpoint.
This would require to add a new column (and likely a new index) on a table, and update the ingestion.

### DB Migration

For example:
```postgresql
-- V042_add_$app_$column.sql
alter table app_acs_store add column my_new_column text;
```

Note:
- The new column is nullable (not-`NOT NULL`), this is because for most templates it will be `null`.

### Handling new data

To handle new data, you will need to update the ingestion code for it. In `XXXAcsStoreRowData`:
- The class will need to define the new column.
- The `indexColumns` should include the new column with its value.
- The companion object's `fromCreatedEvent` should assign the value from the contract's payload.

For example:

```scala
// XXXAcsStoreRowData
case class XXXAcsStoreRowData(
                               contract: Contract[?, ?],
                               contractExpiresAt: Option[Timestamp],
                               fieldThatWasThereBefore: Option[String],
                               myNewColumn: Option[String]
                             ) extends AcsRowData {
  def indexColumns: Seq[(String, IndexCoilumnValue[?])] = Seq(
    "field_that_was_there_before" -> fieldThatWasThereBefore,
    "my_new_column" -> myNewColumn,
  )
}
// [...]
def fromCreatedEvent(
                      createdEvent: CreatedEvent,
                      createdEventBlob: ByteString,
                    ): Either[String, ScanAcsStoreRowData] = {
  // TODO(#8125) Switch to map lookups instead
  // [omitted the match and many branches, you'd add your own]
  case t if t == QualifiedName(TheContract.TEMPLATE_ID_WITH_PACKAGE_ID) =>
    tryToDecode(TheContract.COMPANION, createdEvent, createdEventBlob) {
      contract =>
        XXXAcsStoreRowData(
          contract = contract,
          contractExpiresAt = None,
          fieldThatWasThereBefore = None,
          myNewColumn = contract.payload.somewhere,
        )
    }
}
```

### Handling old data (SQL version, **preferred**)

Whenever possible, old data should be populated using the same Flyway migration that adds the new column.
This avoids re-ingesting data from the ledger.

To do so, the value for the new column is extracted from the create_arguments
using the [JSONB functions and operators](https://www.postgresql.org/docs/current/functions-json.html) included in Postgres.

For example, for the migration in the previous section:
```postgresql
-- V042_add_$app_$column.sql
alter table app_acs_store add column my_new_column text;

update app_acs_store set my_new_column = create_arguments->'a'->'path'->'to'->>'somewhere';
```

If the update fails, Flyway will revert the entire migration.

### Handling old data (store_descriptors version)

If populating the new column with SQL is not possible, you can force the store to re-ingest the **entire** ACS and all transactions thereafter.
To do so, you just need to increment the version of the `acsStoreDescriptor` of the store you want to change:

```scala
// DbXXXStore
acsStoreDescriptor = StoreDescriptor(
  version = 42, // assuming it was previously 41
  // Do not modify the values for the other fields
)
```

Modifying the `acsStoreDescriptor` will not affect existing TxLog entries.
The TxLog uses a separate descriptor `txLogDescriptor` (which may also be identical to the ACS descriptor).
Modifying the `txLogDescriptor` will force the store to discard **all** existing TxLog entries and start re-ingesting from the current offset.

## Modifying the contract filter of a store

Sometimes you need to register a new template in your app store (`XXXStore`), adding an `mkFilter(SomeTemplate.COMPANION)(co => aFilter)`.

- If the contract filter is changed in the same release that also introduces the template, you don't need to do anything.
  The store will just start ingesting the contract next time it is deployed.
- If the template already existed in a previous release,
  you will need to increment the store_descriptors version (same as done in the previous section),
  so that the ingestion picks up all the contracts that already existed and any new ones.
  Note that this will re-ingest the **entire** ACS and all transactions thereafter,
  which is a significantly bigger amount of data and will thus take a while to finish, and use more storage.
- Similarly, if you modify the filter predicate (the `co => aFilter` part in the `mkFilter` example above),
  you should increment the store_descriptors version to force a re-ingestion of all data.
