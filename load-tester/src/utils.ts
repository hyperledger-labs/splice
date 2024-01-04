/* @ts-expect-error typings unavailable */
import { randomIntBetween, randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { sleep } from 'k6';
import { z } from 'zod';

export function jsonStringDecoder<Z extends z.ZodTypeAny = z.ZodNever>(
  schema: Z,
  body: string,
): z.infer<Z> | undefined {
  const result = schema.safeParse(JSON.parse(body));
  if (result.success) {
    return result.data;
  } else {
    return undefined;
  }
}

export function getTomorrowMs(): number {
  const now = Date.now();
  const dayToMs = 24 * 60 * 60 * 1000;

  return new Date(now + dayToMs).getTime() * 1000;
}

export function pickTwoRandom(nums: number): [number, number] {
  const arrayOfNums = Array.from({ length: nums }, (_, n) => n);

  const first = randomIntBetween(0, nums - 1);
  const second = randomItem(arrayOfNums.filter(n => n != first));

  return [first, second];
}

export function syncRetryUndefined<A>(action: () => A | undefined): A | undefined {
  let retries = 5;
  let final = undefined;
  while (retries >= 0) {
    const result = action();
    if (typeof result === 'undefined') {
      sleep(1);
    } else {
      final = result;
      break;
    }
    retries = retries - 1;
  }

  return final;
}
