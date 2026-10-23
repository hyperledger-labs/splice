// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'fs';
import * as semver from 'semver';
import { SPLICE_ROOT } from '@canton-network/splice-pulumi-common';
import { splitwellConfig } from '@canton-network/splice-pulumi-common/src/config/splitwellConfig';

// Must match the splitwell `minimumInitialization` in DarResourcesGenerator.scala. Older splitwell
// versions depend on amulet versions that the validator unvets, so vetting them again on every
// validator restart would conflict with the validator's unvetting.
// TODO(DACH-NY/canton-network-internal#7639): set this automatically from source of truth
const minDarVersion = '0.1.20';

if (splitwellConfig?.maxDarVersion && semver.lt(splitwellConfig.maxDarVersion, minDarVersion)) {
  throw new Error(
    `splitwell.maxDarVersion ${splitwellConfig.maxDarVersion} is below the minimum supported splitwell version ${minDarVersion}`
  );
}

export const splitwellDarPaths = fs
  .readdirSync(`${SPLICE_ROOT}/daml/dars`)
  .filter(file => {
    const match = file.match(/splitwell-(\d+\.\d+\.\d+)\.dar/);
    if (match) {
      const darVersion = match[1];
      return (
        semver.gte(darVersion, minDarVersion) &&
        (splitwellConfig?.maxDarVersion
          ? semver.gte(splitwellConfig.maxDarVersion, darVersion)
          : true)
      );
    }
    return false;
  })
  .map(file => `splice-node/dars/${file}`);
