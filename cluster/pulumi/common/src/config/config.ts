import * as pulumi from '@pulumi/pulumi';
import * as util from 'node:util';

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
    this.pulumiProjectConfig =
      this.configuration.pulumiProjectConfig[pulumi.getProject()] ||
      this.configuration.pulumiProjectConfig.default;
    console.error(
      'Loaded cluster configuration',
      util.inspect(this.configuration, {
        depth: null,
        maxStringLength: null,
      })
    );
  }
}

export const spliceConfig: CnConfig = new CnConfig();
