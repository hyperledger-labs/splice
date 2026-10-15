// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describeUnexpected, exhaust, sentCount, unexpectedCount } from '../probe.ts';
import type { Check } from './index.ts';

const MAX_UNEXPECTED_RATIO = 0.01;

export const enforcedPerIp: Check = {
  name: 'enforced-per-ip',
  async run(probe, component) {
    const burst = await exhaust(probe, component.maxRequestsPerIp);
    const unexpected = describeUnexpected(burst);

    if (unexpectedCount(burst) > MAX_UNEXPECTED_RATIO * sentCount(burst)) {
      return `too many unexpected responses${unexpected}`;
    }
    if (burst.exhausted) {
      return undefined;
    }
    if (burst.seconds > component.windowSeconds) {
      return `inconclusive: sending took ${burst.seconds}s, longer than the ${component.windowSeconds}s window`;
    }
    return `limit not enforced: ${burst.accepted} requests accepted, none rate limited${unexpected}`;
  },
};
