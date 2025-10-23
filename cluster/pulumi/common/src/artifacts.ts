// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

type CnChartVersion =
  | { type: 'local' }
  | {
      type: 'remote';
      version: string;
    };

// TODO: I'd propose to disable this rule globally.
// eslint-disable-next-line @typescript-eslint/no-namespace
namespace CnChartVersion {
  export function parse(version: string | undefined): CnChartVersion {
    return version && version.length > 0 && version !== 'local'
      ? {
          type: 'remote',
          version: version,
        }
      : { type: 'local' };
  }

  export function stringify(version: CnChartVersion): string {
    return version.type === 'remote' ? version.version : 'local';
  }
}

export { CnChartVersion };
