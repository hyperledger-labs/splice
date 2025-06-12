-- TODO (DACH-NY/canton-network-internal#362) put this file somewhere that makes sense

-- in this example:
-- experiment_dataset is the name of the BigQuery dataset
-- da-cn-ci-2 is the GCP project
-- us-central1 is the GCP region
-- cilr_sv_5_bq_experiment_cxn

create table experiment_dataset.transactions as
  SELECT * FROM EXTERNAL_QUERY("da-cn-ci-2.us-central1.cilr_sv_5_bq_experiment_cxn", "SELECT * FROM scan_sv_5.update_history_transactions;");

-- if the underlying scan postgres hosts multiple scans, history_id must be
-- queried on to partition them. In practice production cloudsqls host one
-- scan each, so this is unneeded
create table experiment_dataset.creates as
  SELECT * FROM EXTERNAL_QUERY("da-cn-ci-2.us-central1.cilr_sv_5_bq_experiment_cxn", "SELECT * FROM scan_sv_5.update_history_creates;");

create table experiment_dataset.exercises as
  SELECT * FROM EXTERNAL_QUERY("da-cn-ci-2.us-central1.cilr_sv_5_bq_experiment_cxn", "SELECT * FROM scan_sv_5.update_history_exercises;");
