// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'fs';
import * as yaml from 'js-yaml';
import { merge } from 'lodash';

import { spliceEnvConfig } from './envConfig';

function loadClusterYamlConfig(): unknown {
  const baseConfig = readAndParseYaml(
    `${spliceEnvConfig.context.splicePath}/cluster/deployment/config.yaml`
  );
  // Load an additional common overrides config if it exists;
  // if the file is identical to the base config for some reason, loading it will not change anything.
  const commonOverridesConfigPath = `${spliceEnvConfig.context.clusterPath()}/../config.yaml`;
  const commonOverridesConfig = fs.existsSync(commonOverridesConfigPath)
    ? readAndParseYaml(commonOverridesConfigPath)
    : {};
  const clusterOverridesConfig = readAndParseYaml(
    `${spliceEnvConfig.context.clusterPath()}/config.yaml`
  );
  return merge({}, baseConfig, commonOverridesConfig, clusterOverridesConfig);
}

function readAndParseYaml(filePath: string): unknown {
  try {
    const fileContents = fs.readFileSync(filePath, 'utf8');
    return yaml.load(fileContents);
  } catch (error) {
    console.error(`Error reading or parsing YAML file: ${filePath}`, error);
    throw error;
  }
}

export const clusterYamlConfig: unknown = loadClusterYamlConfig();

export function clusterSubConfig(key: string): unknown {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return (clusterYamlConfig as any)[key] || {};
}
