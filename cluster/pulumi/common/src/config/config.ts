import * as pulumi from '@pulumi/pulumi';
import * as util from 'node:util';
import { merge } from 'lodash';

import { clusterYamlConfig } from './configLoader';
import { Config, ConfigSchema, PulumiProjectConfig } from './configSchema';
import { spliceEnvConfig, SpliceEnvConfig } from './envConfig';

class CnConfig {
  public readonly configuration: Config;
  public readonly envConfig: SpliceEnvConfig;
  public readonly pulumiProjectConfig: PulumiProjectConfig;

  constructor() {
    this.envConfig = spliceEnvConfig;
    this.configuration = ConfigSchema.parse(clusterYamlConfig);
    const pulumiProjectName =
      spliceEnvConfig.optionalEnv('CONFIG_PROJECT_NAME') || pulumi.getProject();
    this.pulumiProjectConfig = merge(
      {},
      this.configuration.pulumiProjectConfig.default,
      this.configuration.pulumiProjectConfig[pulumiProjectName]
    );
    console.error(
      'Loaded cluster configuration',
      util.inspect(this.configuration, {
        depth: null,
        maxStringLength: null,
      })
    );
    console.error(
      // see dump-config-common: `CONFIG_PROJECT_NAME` is used for a fix when dumping the generated resources
      `Loaded project ${pulumiProjectName} configuration`,
      util.inspect(this.pulumiProjectConfig, {
        depth: null,
        maxStringLength: null,
      })
    );
  }
}

export const spliceConfig: CnConfig = new CnConfig();
