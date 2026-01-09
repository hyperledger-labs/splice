// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { loadClusterYamlConfig, getMainConfigPath } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configLoader';
import { writeFileSync } from 'fs';
import { dump } from 'js-yaml';
import { dirname, resolve } from 'path';

const config = loadClusterYamlConfig();
const header = '# This file is generated automatically. Do not edit directly.\n';
const resolvedConfigYaml = dump(config, {
  sortKeys: true,
  lineWidth: -1,
  noRefs: true,
  forceQuotes: true,
});
const resolvedConfigPath = resolve(dirname(getMainConfigPath()), 'config.resolved.yaml');
writeFileSync(resolvedConfigPath, `${header}${resolvedConfigYaml}`);
