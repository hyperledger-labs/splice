import * as fs from 'fs';
import * as yaml from 'js-yaml';
import * as util from 'node:util';
import { merge } from 'lodash';

import { Config, ConfigSchema } from './config/configSchema';
import {
  clusterPath,
  deploymentFolderPath,
  extracted,
  initEnvConfig,
  requiredValue,
} from './config/envConfig';

import Dict = NodeJS.Dict;

class CnConfig {
  env: Dict<string>;
  public readonly configuration: Config;

  constructor() {
    initEnvConfig();
    // eslint-disable-next-line no-process-env
    this.env = process.env;
    this.configuration = this.loadClusterYamlConfig();
    console.error(
      'Loaded cluster configuration',
      util.inspect(this.configuration, {
        depth: null,
        maxStringLength: null,
      })
    );
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
    const flag = extracted(defaultFlag, varVal, flagName);

    console.error(`Environment Flag ${flagName} = ${flag} (${varVal})`);

    return flag;
  }

  private loadClusterYamlConfig(): Config {
    const clusterBaseConfig = this.readAndParseYaml(`${deploymentFolderPath}/config.yaml`);
    const clusterOverridesConfig = this.readAndParseYaml(`${clusterPath()}/config.yaml`);
    return ConfigSchema.parse(merge({}, clusterBaseConfig, clusterOverridesConfig));
  }

  private readAndParseYaml(filePath: string): unknown {
    try {
      if (fs.existsSync(filePath)) {
        const fileContents = fs.readFileSync(filePath, 'utf8');
        return yaml.load(fileContents);
      } else {
        console.error(`File does not exist: ${filePath}`);
        return {};
      }
    } catch (error) {
      console.error(`Error reading or parsing YAML file: ${filePath}`, error);
      throw error;
    }
  }
}

export const config: CnConfig = new CnConfig();
