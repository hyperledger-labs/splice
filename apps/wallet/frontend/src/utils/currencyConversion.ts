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
 * amount = 4, currency = USD, amuletPriceToShow = 0.5 (Amulet/USD)
 */
export function convertCurrency(
  originalAmount: BigNumber,
  originalCurrency: Unit,
  amuletPrice: BigNumber
): ConvertedCurrency {
  if (originalCurrency === 'CC') {
    return {
      amount: originalAmount.times(amuletPrice),
      currency: 'USD',
      amuletPriceToShow: new BigNumber(1).div(amuletPrice),
    };
  } else {
    return {
      amount: originalAmount.div(amuletPrice),
      currency: 'CC',
      amuletPriceToShow: amuletPrice, // already in USD/CC
    };
  }
}
