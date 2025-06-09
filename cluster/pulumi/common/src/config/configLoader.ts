// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'fs';
import * as yaml from 'js-yaml';
import { merge } from 'lodash';

import { spliceEnvConfig } from './envConfig';

function loadClusterYamlConfig() {
  const clusterBaseConfig = readAndParseYaml(
    `${spliceEnvConfig.context.splicePath}/cluster/deployment/config.yaml`
  );
  const clusterOverridesConfig = readAndParseYaml(
    `${spliceEnvConfig.context.clusterPath()}/config.yaml`
  );
  return merge({}, clusterBaseConfig, clusterOverridesConfig);
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

export const clusterYamlConfig = loadClusterYamlConfig();
