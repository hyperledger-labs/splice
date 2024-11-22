import * as util from 'node:util';

import { clusterYamlConfig } from './configLoader';
import { Config, ConfigSchema } from './configSchema';
import { spliceEnvConfig, SpliceEnvConfig } from './envConfig';

class CnConfig {
  public readonly configuration: Config;
  public readonly envConfig: SpliceEnvConfig;

  constructor() {
    this.envConfig = spliceEnvConfig;
    this.configuration = ConfigSchema.parse(clusterYamlConfig);
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
