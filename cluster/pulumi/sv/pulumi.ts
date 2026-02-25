// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as automation from '@pulumi/pulumi/automation';

import { allSvNamesToDeploy } from '../common-sv/src/dsoConfig';
import { DeploySvRunbook, isDevNet } from '../common/src/config';
import { pulumiOptsWithPrefix, stack } from '../pulumi';

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

export async function stackForSv(
  sv: string,
  requiresExistingStack: boolean
): Promise<automation.Stack> {
  return stack('sv', `sv.${sv}`, requiresExistingStack, {
    SPLICE_SV: sv,
  });
}

export const svsToDeploy = allSvNamesToDeploy;

export function runSvProjectForAllSvs<T>(
  operation: string,
  runForStack: (stack: automation.Stack, sv: string) => Promise<T>,
  requiresExistingStack: boolean,
  // allow the ability to force run for the runbook in certain cases
  // this also requires that the cluster is a dev cluster
  // used to ensure down/refresh always takes care of the runbook as well
  forceSvRunbook: boolean = false
): { name: string; promise: Promise<T> }[] {
  const svsToRunFor = svsToDeploy.concat(
    !DeploySvRunbook && forceSvRunbook && isDevNet ? ['sv'] : []
  );
  console.log(`Running for svs ${JSON.stringify(svsToRunFor)}`);
  return svsToRunFor.map(sv => {
    console.error(`Adding operation for sv ${sv}`);
    return {
      name: `${operation}-sv-${sv}`,
      promise: (async () => {
        const stack = await stackForSv(sv, requiresExistingStack);
        return await runForStack(stack, sv);
      })(),
    };
  });
}
