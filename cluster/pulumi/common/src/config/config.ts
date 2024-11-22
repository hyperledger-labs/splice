import * as fs from 'fs';
import * as yaml from 'js-yaml';
import * as util from 'node:util';
import { merge } from 'lodash';

import { Config, ConfigSchema } from './configSchema';
import { spliceEnvConfig, SpliceEnvConfig } from './envConfig';

class CnConfig {
  public readonly configuration: Config;
  public readonly envConfig: SpliceEnvConfig;
  public readonly clusterConfig: unknown;

  constructor() {
    this.envConfig = spliceEnvConfig;
    this.clusterConfig = this.loadClusterYamlConfig();
    this.configuration = ConfigSchema.parse(this.clusterConfig);
    console.error(
      'Loaded cluster configuration',
      util.inspect(this.configuration, {
        depth: null,
        maxStringLength: null,
      })
    );
  }

  private loadClusterYamlConfig() {
    const clusterBaseConfig = this.readAndParseYaml(
      `${this.envConfig.context.deploymentFolderPath}/config.yaml`
    );
    const clusterOverridesConfig = this.readAndParseYaml(
      `${this.envConfig.context.clusterPath()}/config.yaml`
    );
    return merge({}, clusterBaseConfig, clusterOverridesConfig);
  }

  private readAndParseYaml(filePath: string): unknown {
    try {
      const fileContents = fs.readFileSync(filePath, 'utf8');
      return yaml.load(fileContents);
    } catch (error) {
      console.error(`Error reading or parsing YAML file: ${filePath}`, error);
      throw error;
    }
  }
}

export const spliceConfig: CnConfig = new CnConfig();
