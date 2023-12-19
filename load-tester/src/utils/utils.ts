/* @ts-expect-error typings unavailable */
import { randomIntBetween, randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { z } from 'zod';

import { ValidatorClient } from '../client/validator';

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

export function doIfOnboarded(validatorClient: ValidatorClient, action: () => void): void {
  const userStatus = validatorClient.v0.wallet.userStatus();
  if (userStatus?.user_onboarded && userStatus?.user_wallet_installed) {
    action();
  } else {
    validatorClient.v0.register();
  }
}

interface MetricsContext {
  completed: Counter;
  latency: Trend;
}

export function sendAndWaitForTransferOffer(
  sender: ValidatorClient,
  receiver: ValidatorClient,
  metrics: MetricsContext,
): void {
  const startTime = Date.now();

  const transferOffer = sender.v0.wallet.createTransferOffer('1', receiver.partyId()!);
  if (transferOffer) {
    let receivingOffer = receiver.v0.wallet
      .listTransferOffers()
      ?.offers.find(o => o.contract_id === transferOffer.offer_contract_id);
    let retries = 5;
    while (retries >= 0) {
      if (!receivingOffer) {
        sleep(1);
        receivingOffer = receiver.v0.wallet
          .listTransferOffers()
          ?.offers.find(o => o.contract_id === transferOffer.offer_contract_id);
      }
      retries = retries - 1;
    }

    if (receivingOffer) {
      receiver.v0.wallet.acceptTransferOffer(receivingOffer.contract_id);
      metrics.completed.add(1);
      // TODO(#9052) -- include time to show up in transaction history
      metrics.latency.add(Date.now() - startTime);
    }
  } else {
    console.error('We expected a transfer offer from the sending side');
  }
}
