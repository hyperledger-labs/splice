// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import BigNumber from 'bignumber.js';

import { Unit } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';

export interface ConvertedCurrency {
  amount: BigNumber;
  currency: Unit;
  amuletPriceToShow: BigNumber;
}

/**
 * Converts the `originalAmount` of `originalCurrency` to the amount in the other currency.
 *
 * E.g.:
 * originalAmount = 2, originalCurrency = CC, amuletPrice = 2 (USD/CC)
 * would return:
 * amount = 4, currency = USD, amuletPriceToShow = 0.5 (CC/USD)
 */
export function convertCurrency(
  originalAmount: BigNumber,
  originalCurrency: Unit,
  amuletPrice: BigNumber
): ConvertedCurrency {
  if (originalCurrency === 'AmuletUnit') {
    return {
      amount: originalAmount.times(amuletPrice),
      currency: 'USDUnit',
      amuletPriceToShow: new BigNumber(1).div(amuletPrice),
    };
  } else {
    return {
      amount: originalAmount.div(amuletPrice),
      currency: 'AmuletUnit',
      amuletPriceToShow: amuletPrice, // already in USD/CC
    };
  }
}
