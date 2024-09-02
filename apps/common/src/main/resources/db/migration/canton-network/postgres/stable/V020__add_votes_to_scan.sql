alter table scan_acs_store
    add column vote_action_requiring_confirmation jsonb;
alter table scan_acs_store
    add column vote_requester_name text;
alter table scan_acs_store
    add column vote_request_tracking_cid text;

alter table scan_txlog_store
    add column vote_action_name text;
alter table scan_txlog_store
    add column vote_accepted boolean;
alter table scan_txlog_store
    add column vote_requester_name text;
alter table scan_txlog_store
    add column vote_effective_at text;
