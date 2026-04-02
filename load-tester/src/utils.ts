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
    // Pick two validators independently — they may be the same validator,
    // giving a natural mix of intra- and cross-validator transfers.
    const senderValidatorIndex = randomIntBetween(0, validators.length - 1);
    const recipientValidatorIndex = randomIntBetween(0, validators.length - 1);
    // Pick the admin validator independently so that admin balance checks
    // and top-ups are spread across all pods rather than always doubling up
    // on the sender's or recipient's pod.
    const adminValidatorIndex = randomIntBetween(0, validators.length - 1);

    const senderValidator = validators[senderValidatorIndex];
    const recipientValidator = validators[recipientValidatorIndex];
    const adminValidator = validators[adminValidatorIndex];

    // Pick random users within each validator
    const senderIndex = randomIntBetween(0, senderValidator.userTokens.length - 1);
    let recipientIndex = randomIntBetween(0, recipientValidator.userTokens.length - 1);

    // If both land on the same validator, make sure we pick different users
    if (senderValidatorIndex === recipientValidatorIndex && senderIndex === recipientIndex) {
      const others = Array.from(
        { length: recipientValidator.userTokens.length },
        (_, n) => n,
      ).filter(n => n !== senderIndex);
      recipientIndex = randomItem(others);
    }

    const senderToken = senderValidator.userTokens[senderIndex];
    const recipientToken = recipientValidator.userTokens[recipientIndex];

    const senderFeatured = senderValidator.userFeatured[senderIndex];
    const recipientFeatured = recipientValidator.userFeatured[recipientIndex];

    const adminClient = new ValidatorClient(
      adminValidator.walletBaseUrl,
      adminValidator.adminToken,
      undefined,
    );
    const senderClient = new ValidatorClient(
      senderValidator.walletBaseUrl,
      senderToken,
      senderFeatured,
    );
    const recipientClient = new ValidatorClient(
      recipientValidator.walletBaseUrl,
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
  const maxTotalSeconds = 60; // cap total polling time at 60s
  const initialDelaySeconds = 0.2; // start at 200ms
  const maxDelaySeconds = 5; // cap individual delay at 5s
  const backoffFactor = 2;

  let elapsed = 0;
  let delay = initialDelaySeconds;

  while (elapsed < maxTotalSeconds) {
    const result = action();
    if (condition(result)) {
      return result;
    }
    sleep(delay);
    elapsed += delay;
    delay = Math.min(delay * backoffFactor, maxDelaySeconds);
  }

  return undefined;
}

export function syncRetryUndefined<A>(action: () => A | undefined): A | undefined {
  return syncRetryUntil(action, result => typeof result !== 'undefined');
}
