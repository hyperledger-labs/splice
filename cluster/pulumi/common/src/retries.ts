// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';

export async function retry<T>(
  name: string,
  delayMs: number,
  retries: number,
  action: () => Promise<T>
): Promise<T> {
  try {
    return await action();
  } catch (e) {
    const maxRetryDelayMs = 10_000;
    await pulumi.log.error(`Failed '${name}'. Error: ${JSON.stringify(e)}.`);
    if (0 < retries) {
      await new Promise(resolve => setTimeout(resolve, delayMs));
      return await retry(name, Math.min(delayMs * 2 - 1, maxRetryDelayMs), retries - 1, action);
    } else {
      return Promise.reject(`Exhausted retries. Last error: ${e}.`);
    }
  }
}
