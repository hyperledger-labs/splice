// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as path from 'path';

import { readAndParseYaml } from './configLoader';

const scanYamlPath = path.join(__dirname, '../../../../../apps/scan/src/main/openapi/scan.yaml');

export function parseScanYamlEndpoints(): string[] {
  // TODO (DACH-NY/canton-network-internal#3697) minimal type to retrieve paths
  const yaml = readAndParseYaml(scanYamlPath) as { paths: { [p: string]: unknown } };
  const paths = yaml.paths || {};

  const endpoints = new Set<string>();

  for (const path of Object.keys(paths)) {
    // Prepend /api/scan prefix
    let fullPath = '/api/scan' + path;

    // Strip to segment before first {
    const paramIndex = fullPath.indexOf('{');
    if (paramIndex !== -1) {
      // Find the / before the {
      const lastSlash = fullPath.lastIndexOf('/', paramIndex);
      fullPath = fullPath.substring(0, lastSlash);
    }

    endpoints.add(fullPath);
  }

  return Array.from(endpoints).sort();
}
