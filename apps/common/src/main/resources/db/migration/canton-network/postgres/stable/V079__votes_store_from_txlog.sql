-- Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Populates votes_store_results with the vote results that the scan txlog already has, so that backfilling
-- (see VotesStoreBackfilling) only has to fetch what this scan does not have itself from its peers.

-- The txlog and votes_store_results are ingested in the same SQL transaction (and keyed by the same store id),
-- so after this migration they contain the same vote results from the first update that the txlog ingested.

-- On a database that has no scan txlog yet, e.g., because this is the first start of a scan,
-- the statements in this script do not do anything.

-- Each statement starts with the txlog stores of the scan app, together with the key value store that
-- belongs to the same scan app (same party and participant).

-- As of now, completing a vote is always the root exercise of a transaction, so its event number is 0.
-- The event id in the txlog has format '#<update id>:<node id>'.
-- Timestamps are ISO-8601 strings (e.g., 2024-03-01T10:00:00.123456Z), converted to microseconds since the epoch.
-- Votes that are in votes_store_results already (ingested live) are safely skipped.
with stores as (select txlog.id as txlog_store_id, kv.id as kv_store_id
                from store_descriptors txlog
                         join store_descriptors kv
                              on kv.descriptor ->> 'name' = 'ScanKeyValueStore'
                                  and kv.descriptor ->> 'party' = txlog.descriptor ->> 'party'
                                  and kv.descriptor ->> 'participant' = txlog.descriptor ->> 'participant'
                where txlog.descriptor ->> 'name' = 'DbScanStore'
                  and (txlog.descriptor ->> 'version')::int = 1)
insert
into votes_store_results(store_id, record_time, synchronizer_id, update_id, event_number,
                         vote_action_name, vote_requester_name, vote_accepted, vote_tracking_cid,
                         vote_completed_at, vote_effective_at, choice_result)
select t.store_id,
       t.record_time,
       t.domain_id,
       substring(t.event_id from '^#?(.*):[0-9]+$'),
       0,
       t.vote_action_name,
       t.vote_requester_name,
       t.vote_accepted,
       coalesce(t.entry_data -> 'result' -> 'request' ->> 'trackingCid', ''),
       (extract(epoch from (t.entry_data -> 'result' ->> 'completedAt')::timestamptz) * 1000000)::bigint,
       (extract(epoch from t.vote_effective_at::timestamptz) * 1000000)::bigint,
       t.entry_data -> 'result'
from scan_txlog_store t
         join stores s on s.txlog_store_id = t.store_id
where t.entry_type = 'vot'
  and t.entry_data -> 'result' is not null
on conflict do nothing;

-- Set the backfilling cursor to the earliest record time ingested by the txlog.
with stores as (select txlog.id as txlog_store_id, kv.id as kv_store_id
                from store_descriptors txlog
                         join store_descriptors kv
                              on kv.descriptor ->> 'name' = 'ScanKeyValueStore'
                                  and kv.descriptor ->> 'party' = txlog.descriptor ->> 'party'
                                  and kv.descriptor ->> 'participant' = txlog.descriptor ->> 'participant'
                where txlog.descriptor ->> 'name' = 'DbScanStore'
                  and (txlog.descriptor ->> 'version')::int = 1)
insert
into key_value_store(key, value, store_id)
select 'votes_store_backfill_cursor_' || s.txlog_store_id,
       jsonb_build_object('recordTime', min(u.record_time), 'eventNumber', 0),
       s.kv_store_id
from stores s
         join txlog_first_ingested_update u on u.store_id = s.txlog_store_id
where u.migration_id = (select min(m.migration_id)
                        from txlog_first_ingested_update m
                        where m.store_id = s.txlog_store_id)
group by s.txlog_store_id, s.kv_store_id;

-- A fully backfilled txlog contains every vote result since genesis, so the votes store is complete as well.
with stores as (select txlog.id as txlog_store_id, kv.id as kv_store_id
                from store_descriptors txlog
                         join store_descriptors kv
                              on kv.descriptor ->> 'name' = 'ScanKeyValueStore'
                                  and kv.descriptor ->> 'party' = txlog.descriptor ->> 'party'
                                  and kv.descriptor ->> 'participant' = txlog.descriptor ->> 'participant'
                where txlog.descriptor ->> 'name' = 'DbScanStore'
                  and (txlog.descriptor ->> 'version')::int = 1)
insert
into key_value_store(key, value, store_id)
select 'votes_store_backfill_complete_' || s.txlog_store_id,
       to_jsonb(true),
       s.kv_store_id
from stores s
         join txlog_backfilling_status b on b.store_id = s.txlog_store_id
where b.backfilling_complete;
