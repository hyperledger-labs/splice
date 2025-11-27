import { afterAll, expect, jest, test } from '@jest/globals';

import { retry } from './retries';

// suppresses error logs from retry to get cleaner test output
const consoleErrorMock = jest.spyOn(console, 'error').mockImplementation(() => {});
afterAll(() => {
  consoleErrorMock.mockRestore();
});

test('retry runs the action again if it fails', async () => {
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
