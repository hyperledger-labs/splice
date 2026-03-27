// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
/* @ts-expect-error typings unavailable */
import { randomIntBetween, randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { sleep } from 'k6';
import { z } from 'zod';

import { ValidatorClient } from './client/validator/validator';
import { ValidatorConf } from './test/generate-load';

export function jsonStringDecoder<Z extends z.ZodTypeAny = z.ZodNever>(
  schema: Z,
  body: string,
): z.infer<Z> | undefined {
  const result = schema.safeParse(JSON.parse(body));
  if (result.success) {
    return result.data;
  } else {
    console.warn(`Failed to decode schema: ${result.error}`);
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

export function pickTwoRandomUsers(validators: ValidatorConf[]): {
  adminClient: ValidatorClient;
  senderClient: ValidatorClient;
  recipientClient: ValidatorClient;
} {
  if (validators.length > 1) {
    // Pick two random available validators
    const [validator1Index, validator2Index] = pickTwoRandom(validators.length);

    const validator1 = validators[validator1Index];
    const validator2 = validators[validator2Index];

    // Pick two random users from the validators
    const senderIndex = randomIntBetween(0, validator1.userTokens.length - 1);
    const recipientIndex = randomIntBetween(0, validator2.userTokens.length - 1);

    const senderToken = validator1.userTokens[senderIndex];
    const recipientToken = validator2.userTokens[recipientIndex];

    const senderFeatured = validator1.userFeatured[senderIndex];
    const recipientFeatured = validator2.userFeatured[recipientIndex];

    const adminClient = new ValidatorClient(
      validator1.walletBaseUrl,
      validator1.adminToken,
      undefined,
    );
    const senderClient = new ValidatorClient(validator1.walletBaseUrl, senderToken, senderFeatured);
    const recipientClient = new ValidatorClient(
      validator2.walletBaseUrl,
      recipientToken,
      recipientFeatured,
    );

    return { adminClient, senderClient, recipientClient };
  } else {
    const validatorConf: ValidatorConf = validators[0];
    const { adminToken, walletBaseUrl, userTokens, userFeatured } = validatorConf;

    // Pick two random users from that validator
    const [senderIndex, recipientIndex] = pickTwoRandom(userTokens.length);

    const senderToken = userTokens[senderIndex];
    const recipientToken = userTokens[recipientIndex];

    const senderFeatured = userFeatured[senderIndex];
    const recipientFeatured = userFeatured[recipientIndex];

    const adminClient = new ValidatorClient(walletBaseUrl, adminToken, undefined);
    const senderClient = new ValidatorClient(walletBaseUrl, senderToken, senderFeatured);
    const recipientClient = new ValidatorClient(walletBaseUrl, recipientToken, recipientFeatured);

    return { adminClient, senderClient, recipientClient };
  }
}

export function syncRetryUntil<A>(
  action: () => A | undefined,
  condition: (result: A | undefined) => boolean,
): A | undefined {
  let retries = 200; // The sleep is 200ms, so a larger retry value is fine
  let final = undefined;

  while (retries >= 0) {
    const result = action();
    if (condition(result)) {
      final = result;
      break;
    } else {
      sleep(0.2);
    }
    retries = retries - 1;
  }

  return final;
}

export function syncRetryUndefined<A>(action: () => A | undefined): A | undefined {
  return syncRetryUntil(action, result => typeof result !== 'undefined');
}
