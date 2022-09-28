Disaster Recovery
=================

* How to backup and restore the application?

  * backup the validator's participant DB: https://docs.daml.com/canton/usermanual/operational_processes.html#backup-and-restore
  * backup your app backend's persistent cache, if rehydration from the
    participant node is not possible within the target recovery time
  * ensure that you periodically test your backup/restore procedure

* How to deal with data loss on the participant node?

  * this would be due to the complete loss of the participant node, and having
    to restore from a backup that is missing some of the most recent writes to
    the participant node's DB
  * we recommend to run validator nodes for production apps with an HA
    database and synchronous replication to minimize the chance of data loss;
    see https://docs.daml.com/canton/usermanual/operational_processes.html#database-failover
  * if a data loss happened, then the recovery procedure is necessarily
    specific to the application and to what data was lost. We expect the
    following steps to be helpful.

    1. Determine what data was lost, and whether it can and/or must be
       restored from persistent caches and/or validator nodes of
       counter-parties.
    2. Build a plan for how to restore the data.
    3. Restore the data in your validator node.
    4. Backup persistent caches and rehydrate them from your validator node.

