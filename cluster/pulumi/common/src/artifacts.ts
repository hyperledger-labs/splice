// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export type CnChartVersion =
  | { type: 'local' }
  | {
      type: 'remote';
      version: string;
    };

export function parsedVersion(version: string | undefined): CnChartVersion {
  return version && version.length > 0 && version !== 'local'
    ? {
        type: 'remote',
        version: version,
      }
    : { type: 'local' };
}
