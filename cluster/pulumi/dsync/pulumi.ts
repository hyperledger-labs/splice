// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as automation from '@pulumi/pulumi/automation';

import { allSvNamesToDeploy } from '../common-sv/src/dsoConfig';
import { pulumiOptsWithPrefix, stack } from '../pulumi';
import { allDSyncNodeNames } from '@canton-network/splice-pulumi-common-sv/src/dSyncNode';

export function pulumiOptsForSv(
  sv: string,
  abortSignal: AbortSignal
): {
  parallel: number;
  onOutput: (output: string) => void;
  signal: AbortSignal;
} {
  return pulumiOptsWithPrefix(`[sv=${sv}]`, abortSignal);
}

export async function stackForNode(
  nodeName: string,
  requiresExistingStack: boolean
): Promise<automation.Stack> {
  return stack('dsync', `dsync.${nodeName}`, requiresExistingStack, {
    SPLICE_DSYNC_NODE: nodeName,
  });
}

export const svsToDeploy = allSvNamesToDeploy;

export function runDSyncProjectForDSyncNodes<T>(
  nodesToRunFor: string[],
  operation: string,
  requiresExistingStack: boolean,
  runForStack: (stack: automation.Stack, nodeName: string) => Promise<T>
): Array<{ name: string; promise: Promise<T> }> {
  return nodesToRunFor.map(nodeName => {
    console.error(`Adding operation for DSync node ${nodeName}`);
    return {
      name: `${operation}-dsync-${nodeName}`,
      promise: (async () => {
        const stack = await stackForNode(nodeName, requiresExistingStack);
        return await runForStack(stack, nodeName);
      })(),
    };
  });
}

export function runDSyncProjectForAllNodes<T>(
  operation: string,
  runForStack: (stack: automation.Stack, nodeName: string) => Promise<T>,
  requiresExistingStack: boolean,
): { name: string; promise: Promise<T> }[] {
  const nodesToRunFor = allDSyncNodeNames();
  return runDSyncProjectForDSyncNodes(nodesToRunFor, operation, requiresExistingStack, runForStack);
}
