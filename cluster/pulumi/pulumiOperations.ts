// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as automation from '@pulumi/pulumi/automation';
import { UpOptions } from '@pulumi/pulumi/automation/stack';
import util from 'node:util';

import {
  ensureStackSettingsAreUpToDate,
  Operation,
  PulumiAbortController,
  pulumiOptsWithPrefix,
} from './pulumi';

export function refreshOperation(
  stack: automation.Stack,
  abortController: PulumiAbortController
): Operation {
  return operation(`refresh-${stack.name}`, refreshStack(stack, abortController));
}

export async function refreshStack(
  stack: automation.Stack,
  abortController: PulumiAbortController
): Promise<void> {
  const name = stack.name;
  console.log(`${name} - Refreshing stack`);
  await ensureStackSettingsAreUpToDate(stack);
  await stack.refresh(pulumiOptsWithPrefix(`[${name}]`, abortController.signal)).catch(e => {
    abortController.abort(`${stack.name} - Aborting because of caught exception`);
    throw e;
  });
}

export function downOperation(
  stack: automation.Stack,
  abortController: PulumiAbortController
): Operation {
  return operation(`down-${stack.name}`, downStack(stack, abortController));
}

export async function downStack(
  stack: automation.Stack,
  abortController: PulumiAbortController
): Promise<void> {
  const name = stack.name;
  console.error(`${name} - Refreshing & Destroying stack`);
  try {
    console.error(`[${name}] Refreshing`);
    await stack.refresh(pulumiOptsWithPrefix(`[${name}]`, abortController.signal));
    console.error(`[${name}] Destroying`);
    await stack.destroy(pulumiOptsWithPrefix(`[${name}]`, abortController.signal));
  } catch (e) {
    if (e instanceof automation.ConcurrentUpdateError) {
      console.error(`[${name}] Stack is locked, cancelling and re-running.`);
      await stack.cancel();
      await downStack(stack, abortController);
    } else {
      abortController.abort(`${stack.name} - Aborting because of caught exception`);
      throw e;
    }
  }
}

export function upOperation(
  stack: automation.Stack,
  abortController: PulumiAbortController
): Operation {
  return operation(`up-${stack.name}`, upStack(stack, abortController));
}

export async function upStack(
  stack: automation.Stack,
  abortController: PulumiAbortController
): Promise<void> {
  const name = stack.name;
  return abortableStackOperation(stack, abortController, stack.up(pulumiOptsWithPrefix(`[${name}]`, abortController.signal)), result => result.summary);
}

export async function previewStack(
  stack: automation.Stack,
  abortController: PulumiAbortController
): Promise<void> {
  const name = stack.name;
  return abortableStackOperation(stack, abortController, stack.preview(pulumiOptsWithPrefix(`[${name}]`, abortController.signal)), result => result.changeSummary);
}

function abortableStackOperation<T>(stack: automation.Stack, abortController: PulumiAbortController, op: Promise<T>, resultToSummary: (t: T) => unknown): Promise<void> {
  const name = stack.name;
  return op.then(
    result => {
      console.log(
        `${name} success - ${util.inspect(resultToSummary(result), {
          colors: true,
          depth: null,
          maxStringLength: null,
        })}
        `
      );
      return;
    },
    e => {
      abortController.abort(`${name} - Aborting because of caught exception`);
      throw e;
    }
  );
}

export function previewOperation(
  stack: automation.Stack,
  abortController: PulumiAbortController
): Operation {
  return operation(`preview-${stack.name}`, previewStack(stack, abortController));
}

export function operation(name: string, promise: Promise<void>): Operation {
  return { name, promise };
}
