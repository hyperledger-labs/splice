import { loadYamlFromFile, REPO_ROOT } from './utils';

export const spliceInstanceNames = loadYamlFromFile(
  REPO_ROOT + '/cluster/cn-svc-configs/configs/ui-config-values.yaml'
);
