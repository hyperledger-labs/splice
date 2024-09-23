import * as glob from 'glob';
import { config as dotenvConfig } from 'dotenv';
import { expand } from 'dotenv-expand';

export function requiredValue(value: string | undefined, name: string, msg: string): string {
  if (!value) {
    console.error(
      `FATAL: Environment variable ${name} is undefined. Shutting down.` +
        (msg != '' ? `(should define: ${msg})` : '')
    );
    process.exit(1);
  } else {
    return value;
  }
}

export function extracted(
  defaultFlag: boolean,
  varVal: string | undefined,
  flagName: string
): boolean {
  let flag = defaultFlag;

  if (varVal) {
    const val = varVal.toLowerCase();

    if (val === 't' || val === 'true' || val === 'y' || val === 'yes' || val === '1') {
      flag = true;
    } else if (val === 'f' || val === 'false' || val === 'n' || val === 'no' || val === '0') {
      flag = false;
    } else {
      console.error(
        `FATAL: Flag environment variable ${flagName} has unexpected value: ${varVal}.`
      );
      process.exit(1);
    }
  }
  return flag;
}

export const deploymentFolderPath = `${process.env.REPO_ROOT}/cluster/deployment`;

export function initEnvConfig(): void {
  /*eslint no-process-env: "off"*/
  if (
    extracted(false, process.env.CN_PULUMI_LOAD_ENV_CONFIG_FILE, 'CN_PULUMI_LOAD_ENV_CONFIG_FILE')
  ) {
    const envrcs = [`${process.env.REPO_ROOT}/.envrc.vars`].concat(
      glob.sync(`${process.env.REPO_ROOT}/.envrc.vars.*`)
    );
    const result = expand(dotenvConfig({ path: envrcs }));
    if (result.error) {
      throw new Error(`Failed to load base config ${result.error}`);
    }
    const overrideResult = expand(
      dotenvConfig({
        path: `${clusterPath()}/.envrc.vars`,
        override: true,
      })
    );
    if (overrideResult.error) {
      throw new Error(`Failed to load cluster config ${overrideResult.error}`);
    }
  }
}

export function extractGcpClusterFolderName(): string {
  let gcpclusterbasename = requiredValue(
    process.env.GCP_CLUSTER_BASENAME,
    'GCP_CLUSTER_BASENAME',
    'Cluster must be specified'
  );
  if (gcpclusterbasename?.includes('scratch')) {
    // fix difference between deployment folder name and cluster name
    gcpclusterbasename = gcpclusterbasename.replace('scratch', 'scratchnet');
  } else {
    if (!gcpclusterbasename?.includes('cilr')) {
      gcpclusterbasename = gcpclusterbasename + 'net';
    }
  }
  return gcpclusterbasename;
}

export function clusterPath(): string {
  return `${deploymentFolderPath}/${extractGcpClusterFolderName()}`;
}

initEnvConfig();
