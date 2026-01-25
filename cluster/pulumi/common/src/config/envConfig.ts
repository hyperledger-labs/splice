// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as glob from 'glob';
import { config as dotenvConfig } from 'dotenv';
import { expand } from 'dotenv-expand';

import Dict = NodeJS.Dict;

export class SpliceConfigContext {
  readonly deploymentFolderPath = requiredValue(
    process.env.DEPLOYMENT_DIR,
    'DEPLOYMENT_DIR',
    'Deployment folder must be specified'
  );

  readonly splicePath = requiredValue(
    process.env.SPLICE_ROOT,
    'SPLICE_ROOT',
    'Splice root must be specified'
  );

  extractGcpClusterFolderName(): string {
    const gcpclusterbasename = requiredValue(
      process.env.GCP_CLUSTER_BASENAME,
      'GCP_CLUSTER_BASENAME',
      'Cluster must be specified'
    );

    const clusterToDirectoryNames: Record<string, string> = {
      dev: 'devnet',
      testzrh: 'testnet',
      mainzrh: 'mainnet',
    };

    if (Object.keys(clusterToDirectoryNames).includes(gcpclusterbasename)) {
      return clusterToDirectoryNames[gcpclusterbasename];
    }

    if (gcpclusterbasename?.includes('scratch')) {
      // fix difference between deployment folder name and cluster name
      return gcpclusterbasename.replace('scratch', 'scratchnet');
    }

    return gcpclusterbasename;
  }

  clusterPath(): string {
    return `${this.deploymentFolderPath}/${this.extractGcpClusterFolderName()}`;
  }
}

export class SpliceEnvConfig {
  env: Dict<string>;
  public readonly context: SpliceConfigContext;

  constructor() {
    this.context = new SpliceConfigContext();
    /*eslint no-process-env: "off"*/
    if (
      this.extracted(
        false,
        process.env.CN_PULUMI_LOAD_ENV_CONFIG_FILE,
        'CN_PULUMI_LOAD_ENV_CONFIG_FILE'
      )
    ) {
      const envrcs = [`${process.env.SPLICE_ROOT}/.envrc.vars`].concat(
        glob.sync(`${process.env.SPLICE_ROOT}/.envrc.vars.*`)
      );
      console.error(`Loading environment variables from ${envrcs.join(', ')}`);
      const result = expand(dotenvConfig({ path: envrcs }));
      if (result.error) {
        throw new Error(`Failed to load base config ${result.error}`);
      }
      const overrideResult = expand(
        dotenvConfig({
          path: `${this.context.clusterPath()}/.envrc.vars`,
          override: true,
        })
      );
      if (overrideResult.error) {
        throw new Error(`Failed to load cluster config ${overrideResult.error}`);
      }
    }
    // eslint-disable-next-line no-process-env
    this.env = process.env;
  }

  requireEnv(name: string, msg = ''): string {
    const value = this.env[name];
    return requiredValue(value, name, msg);
  }

  optionalEnv(name: string): string | undefined {
    const value = this.env[name];
    console.error(`Read option env ${name} with value ${value}`);
    return value;
  }

  envFlag(flagName: string, defaultFlag = false): boolean {
    const varVal = this.env[flagName];
    const flag = this.extracted(defaultFlag, varVal, flagName);

    console.error(`Environment Flag ${flagName} = ${flag} (${varVal})`);

    return flag;
  }

  extracted(defaultFlag: boolean, varVal: string | undefined, flagName: string): boolean {
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

function requiredValue(value: string | undefined, name: string, msg: string): string {
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

export const spliceEnvConfig = new SpliceEnvConfig();
