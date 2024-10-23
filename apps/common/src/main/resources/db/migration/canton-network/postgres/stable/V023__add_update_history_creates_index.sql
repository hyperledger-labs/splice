-- Create new index on history_id, template_id_module_name, template_id_entity_name, package_name, row_id)
create index updt_hist_crea_hi_tidmn_tiden_pn_rid on update_history_creates (history_id, template_id_module_name, template_id_entity_name, package_name, row_id);
