import BigNumber from 'bignumber.js';

import { Currency } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';

export interface ConvertedCurrency {
  amount: BigNumber;
  currency: Currency;
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
  originalCurrency: Currency,
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
