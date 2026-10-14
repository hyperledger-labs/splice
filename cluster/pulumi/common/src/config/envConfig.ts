// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import * as glob from 'glob';
import { config as dotenvConfig } from '@dotenvx/dotenvx';

import Dict = NodeJS.Dict;

// Values of env vars whose name matches this are never logged
const sensitiveEnvNamePattern = /SECRET|PASSWORD|TOKEN|CREDENTIAL|KEY/i;

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
      void pulumi.log.debug(`Loading environment variables from ${envrcs.join(', ')}`);
      const result = dotenvConfig({ path: envrcs, quiet: true });
      if (result.error) {
        throw new Error(`Failed to load base config ${result.error}`);
      }
      const overrideResult = dotenvConfig({
        path: `${this.context.clusterPath()}/.envrc.vars`,
        overload: true,
        quiet: true,
      });
      if (overrideResult.error) {
        throw new Error(`Failed to load cluster config ${overrideResult.error}`);
      }
    }

    this.env = process.env;
  }

  requireEnv(name: string, msg = ''): string {
    const value = this.env[name];
    return requiredValue(value, name, msg);
  }

  optionalEnv(name: string): string | undefined {
    const value = this.env[name];
    const loggedValue =
      value !== undefined && sensitiveEnvNamePattern.test(name) ? '<redacted>' : value;
    void pulumi.log.debug(`Read optional env ${name} with value ${loggedValue}`);
    return value;
  }

  envFlag(flagName: string, defaultFlag = false): boolean {
    const varVal = this.env[flagName];
    const flag = this.extracted(defaultFlag, varVal, flagName);

    void pulumi.log.debug(`Environment flag ${flagName} = ${flag} (${varVal})`);

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
        throw new Error(`Flag environment variable ${flagName} has unexpected value: ${varVal}`);
      }
    }
    return flag;
  }
}

function requiredValue(value: string | undefined, name: string, msg: string): string {
  if (!value) {
    throw new Error(
      `Environment variable ${name} is undefined` + (msg != '' ? ` (should define: ${msg})` : '')
    );
  }
  return value;
}

export const spliceEnvConfig = new SpliceEnvConfig();
