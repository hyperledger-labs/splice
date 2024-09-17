ALTER TABLE update_history_transactions ADD COLUMN workflow_id text;
ALTER TABLE update_history_transactions ADD COLUMN command_id text;

ALTER TABLE update_history_creates ADD COLUMN signatories text[];
ALTER TABLE update_history_creates ADD COLUMN observers text[];
ALTER TABLE update_history_creates ADD COLUMN contract_key jsonb;

ALTER TABLE update_history_exercises ADD COLUMN interface_id_package_id text;
ALTER TABLE update_history_exercises ADD COLUMN interface_id_module_name text;
ALTER TABLE update_history_exercises ADD COLUMN interface_id_entity_name text;
ALTER TABLE update_history_exercises ADD COLUMN acting_parties text[];

ALTER TABLE update_history_assignments ADD COLUMN signatories text[];
ALTER TABLE update_history_assignments ADD COLUMN observers text[];
ALTER TABLE update_history_assignments ADD COLUMN contract_key jsonb;
