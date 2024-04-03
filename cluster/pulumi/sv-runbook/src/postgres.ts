import { envFlag, ExactNamespace, loadYamlFromFile, REPO_ROOT } from 'cn-pulumi-common';
import { CloudPostgres, CNPostgres } from 'cn-pulumi-common/src/postgres';

const cloudSqlEnabled = envFlag('SV_RUNBOOK_ENABLE_CLOUD_SQL', false);

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  secretName: string,
  selfHostedValuesFile: string
): CNPostgres | CloudPostgres {
  if (cloudSqlEnabled) {
    return new CloudPostgres(xns, name, secretName);
  } else {
    const values = loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/${selfHostedValuesFile}`
    );
    return new CNPostgres(xns, name, secretName, values);
  }
}
