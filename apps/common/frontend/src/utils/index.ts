// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import BigNumber from 'bignumber.js';

export * from './auth';
export * from './amuletRules';
export * from './helpers';
export * from './voteRequests';

export const medianPriceVotes = (votedPrices: BigNumber[]) => {
  if (votedPrices && votedPrices.length > 0) {
    const sorted = [...votedPrices].sort((a, b) => {
      return a.isEqualTo(b) ? 0 : a.isLessThan(b) ? -1 : 1;
    });
    const length = sorted.length;
    const half = Math.floor(length / 2);
    return length % 2 !== 0 ? sorted[half] : sorted[half - 1].plus(sorted[half]).multipliedBy(0.5);
  }
  return undefined;
};
