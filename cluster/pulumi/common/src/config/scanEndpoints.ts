// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as path from 'path';
import { z } from 'zod';

import { readAndParseYaml } from './configLoader';

const scanYamlPath = path.join(__dirname, '../../../../../apps/scan/src/main/openapi/scan.yaml');

const MinimalOpenApiSchema = z.object({ paths: z.object({}).catchall(z.unknown()).default({}) });

/**
 * Read scan.yaml OpenAPI paths into normalized `/api/scan...` endpoint prefixes.
 * Keep only the static prefix before the first `{...}` segment if the path contains parameters.
 * This preserves only the segment for which simple string prefix matching works.
 */
export function parseScanYamlEndpoints(): string[] {
  const yaml = MinimalOpenApiSchema.parse(readAndParseYaml(scanYamlPath));
  const paths = yaml.paths;

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
