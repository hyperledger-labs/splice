import BigNumber from 'bignumber.js';

import { Currency } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

export interface ConvertedCurrency {
  amount: BigNumber;
  currency: Currency;
  coinPriceToShow: BigNumber;
}

/**
 * Converts the `originalAmount` of `originalCurrency` to the amount in the other currency.
 *
 * E.g.:
 * originalAmount = 2, originalCurrency = CC, coinPrice = 2 (USD/CC)
 * would return:
 * amount = 4, currency = USD, coinPriceToShow = 0.5 (CC/USD)
 */
export function convertCurrency(
  originalAmount: BigNumber,
  originalCurrency: Currency,
  coinPrice: BigNumber
): ConvertedCurrency {
  if (originalCurrency === 'CC') {
    return {
      amount: originalAmount.times(coinPrice),
      currency: 'USD',
      coinPriceToShow: new BigNumber(1).div(coinPrice),
    };
  } else {
    return {
      amount: originalAmount.div(coinPrice),
      currency: 'CC',
      coinPriceToShow: coinPrice, // already in USD/CC
    };
  }
}
