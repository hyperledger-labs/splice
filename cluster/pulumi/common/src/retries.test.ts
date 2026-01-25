// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { afterAll, expect, jest, test } from '@jest/globals';

import { retry } from './retries';

// suppresses error logs from retry to get cleaner test output
const consoleErrorMock = jest.spyOn(console, 'error').mockImplementation(() => {});
afterAll(() => {
  consoleErrorMock.mockRestore();
});

test('retry runs the action again if it fails with an exception', async () => {
  let executionIndex = 0;
  async function unreliableAction(): Promise<void> {
    try {
      if (executionIndex === 0) {
        throw new Error('action failed');
      }
    } finally {
      ++executionIndex;
    }
  }
  await expect(retry('', 0, 1, unreliableAction)).resolves.toBeUndefined();
  expect(executionIndex).toBe(2);
});
