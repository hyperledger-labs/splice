import { config as dotenvConfig } from 'dotenv';
import { expand } from 'dotenv-expand';

import Dict = NodeJS.Dict;

class CnConfig {
  env: Dict<string>;

  /*eslint no-process-env: "off"*/
  constructor() {
    if (
      CnConfig.extracted(
        false,
        process.env.CN_PULUMI_LOAD_ENV_CONFIG_FILE,
        'CN_PULUMI_LOAD_ENV_CONFIG_FILE'
      )
    ) {
      const result = expand(
        dotenvConfig({
          path: [`${process.env.REPO_ROOT}/.envrc.vars`, `${process.env.REPO_ROOT}/.envrc.vars.da`],
        })
      );
      if (result.error) {
        throw new Error(`Failed to load base config ${result.error}`);
      }
      let gcpclusterbasename = CnConfig.requiredValue(
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
      const overrideResult = expand(
        dotenvConfig({
          path: `${process.env.REPO_ROOT}/cluster/deployment/${gcpclusterbasename}/.envrc.vars`,
          override: true,
        })
      );
      if (overrideResult.error) {
        throw new Error(`Failed to load cluster config ${overrideResult.error}`);
      }
    }
    this.env = process.env;
  }

  requireEnv(name: string, msg = ''): string {
    const value = this.env[name];
    return CnConfig.requiredValue(value, name, msg);
  }

  private static requiredValue(value: string | undefined, name: string, msg: string) {
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

  optionalEnv(name: string): string | undefined {
    const value = this.env[name];
    console.error(`Read option env ${name} with value ${value}`);
    return value;
  }

  envFlag(flagName: string, defaultFlag = false): boolean {
    const varVal = this.env[flagName];
    const flag = CnConfig.extracted(defaultFlag, varVal, flagName);

    console.error(`Environment Flag ${flagName} = ${flag} (${varVal})`);

    return flag;
  }

  private static extracted(defaultFlag: boolean, varVal: string | undefined, flagName: string) {
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
}

export const config: CnConfig = new CnConfig();
