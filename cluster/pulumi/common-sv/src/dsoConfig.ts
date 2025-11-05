// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  config,
  isDevNet,
  DeploySvRunbook,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/config';

import { configuredExtraSvs } from './singleSvConfig';

function getDsoSize(): number {
  // If not devnet, enforce 1 sv
  if (!isDevNet) {
    return 1;
  }

  const maxDsoSize = 16;
  const dsoSize = parseInt(
    config.requireEnv(
      'DSO_SIZE',
      `Specify how many foundation SV nodes this cluster should be deployed with. (min 1, max ${maxDsoSize})`
    )
  );

  if (dsoSize < 1) {
    throw new Error('DSO_SIZE must be at least 1');
  }

  if (dsoSize > maxDsoSize) {
    throw new Error(`DSO_SIZE must be at most ${maxDsoSize}`);
  }

  return dsoSize;
}

export const dsoSize = getDsoSize();

// Used by `cnluster --sv1-only`
export const skipExtraSvs = config.envFlag('SPLICE_SKIP_EXTRA_SVS', false);

function getAllSvNamesToDeploy(): string[] {
  const coreSvs = Array.from({ length: dsoSize }, (_, index) => `sv-${index + 1}`);
  const extraSvs = skipExtraSvs ? [] : configuredExtraSvs;
  const svRunbook = DeploySvRunbook ? [svRunbookNodeName] : [];
  return [coreSvs, extraSvs, svRunbook].flat();
}

export const svRunbookNodeName = 'sv';

// use this is if imporing `svConfigs` for `allSvsToDeploy` doesn't work for you because you don't have a pulumi runtime
export const allSvNamesToDeploy = getAllSvNamesToDeploy();
