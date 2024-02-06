import { Contract } from 'common-frontend-utils';
import { test, expect } from 'vitest';

import { Coin } from '@daml.js/canton-coin/lib/CC/Coin';

test('decode contracts from old and new model', async () => {
  const base = {
    template_id: 'foobar',
    contract_id: 'dummyContractId',
    created_event_blob: '',
    created_at: 'dummyCreatedAt',
  };
  const oldPayload = {
    svc: 'svc',
    owner: 'owner',
    amount: {
      initialAmount: '1.0',
      createdAt: { number: '0' },
      ratePerRound: { rate: '0.01' },
    },
  };
  const oldCoin = Contract.decodeOpenAPI({ ...base, payload: oldPayload }, Coin);
  const newPayload = { ...oldPayload, upgrade: null };
  const newCoin = Contract.decodeOpenAPI({ ...base, payload: newPayload }, Coin);
  expect(oldCoin).toStrictEqual(newCoin);
});
