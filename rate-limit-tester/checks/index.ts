// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import type { Component } from '../config.ts';
import type { Probe } from '../probe.ts';
import { enforcedPerIp } from './enforcedPerIp.ts';
import { spoofingNotPossible } from './spoofingNotPossible.ts';

export type Check = {
  name: string;
  // Resolves to a failure reason, or undefined when the check passes.
  run(probe: Probe, component: Component): Promise<string | undefined>;
};

export const CHECKS: Check[] = [enforcedPerIp, spoofingNotPossible];
