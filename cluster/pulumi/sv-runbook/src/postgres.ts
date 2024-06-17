import * as _ from 'lodash';
import {
  clusterSmallDisk,
  ExactNamespace,
  loadYamlFromFile,
  REPO_ROOT,
  config,
} from 'cn-pulumi-common';
import { CloudPostgres, CNPostgres } from 'cn-pulumi-common/src/postgres';

const cloudSqlEnabled = config.envFlag('SV_RUNBOOK_ENABLE_CLOUD_SQL', false);

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  secretName: string,
  selfHostedValuesFile: string
): CNPostgres | CloudPostgres {
  if (cloudSqlEnabled) {
    return new CloudPostgres(xns, name, name, secretName);
  } else {
    const valuesFromFile = loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/${selfHostedValuesFile}`
    );
    const volumeSizeOverride = determineVolumeSizeOverride(valuesFromFile.db?.volumeSize);
    const values = _.merge(valuesFromFile || {}, { db: { volumeSize: volumeSizeOverride } });
    return new CNPostgres(xns, name, name, secretName, values);
  }
}

// A bit complicated because some of the values in our examples are actually lower than the default for CLUSTER_SMALL_DISK
function determineVolumeSizeOverride(volumeSizeFromFile: string | undefined): string | undefined {
  const gigs = (s: string) => parseInt(s.replace('Gi', ''));
  return clusterSmallDisk && volumeSizeFromFile && gigs(volumeSizeFromFile) > 240
    ? '240Gi'
    : undefined;
}
