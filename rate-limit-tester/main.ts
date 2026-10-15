// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { check } from 'k6';
import type { Options, Scenario } from 'k6/options';

import { CHECKS } from './checks/index.ts';
import { loadConfig } from './config.ts';
import { probeFor } from './probe.ts';

const SLOT_SECONDS = 60;

const targets = loadConfig(__ENV.CONFIG).map(component => ({
  component,
  probe: probeFor(component),
}));

// One scenario per (component, check). They share the host's per-IP budget, so each gets its
// own time slot: it starts when the previous slot ends and is cut off at the end of its own.
function buildScenarios(): Record<string, Scenario> {
  const scenarios: Record<string, Scenario> = {};
  let offsetSeconds = 0;
  for (const { component } of targets) {
    for (const rateLimitCheck of CHECKS) {
      scenarios[`${component.name}_${rateLimitCheck.name}`.replace(/[^\w]/g, '_')] = {
        executor: 'shared-iterations',
        vus: 1,
        iterations: 1,
        startTime: `${offsetSeconds}s`,
        maxDuration: `${SLOT_SECONDS}s`,
        gracefulStop: '0s',
        env: { COMPONENT: component.name, CHECK: rateLimitCheck.name },
      };
      offsetSeconds += SLOT_SECONDS;
    }
  }
  return scenarios;
}

export const options: Options = {
  scenarios: buildScenarios(),
  thresholds: { checks: ['rate==1'] },
};

export default async function (): Promise<void> {
  const target = targets.find(({ component }) => component.name === __ENV.COMPONENT);
  const rateLimitCheck = CHECKS.find(({ name }) => name === __ENV.CHECK);
  if (!target || !rateLimitCheck) {
    throw new Error(`unknown scenario ${__ENV.COMPONENT}/${__ENV.CHECK}`);
  }
  const { component, probe } = target;
  const failure = await rateLimitCheck.run(probe, component);
  const label = `${component.name}: ${rateLimitCheck.name}`;
  if (failure) console.error(`${label}: ${failure}`);
  check(failure, { [label]: reason => reason === undefined });
}
