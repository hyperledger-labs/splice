import { Decimal } from 'decimal.js';
import React from 'react';

import { PaymentAmount } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

const AmountDisplay: React.FC<{
  amount?: string;
  currency?: string;
}> = ({ amount, currency }) => {
  if (amount) {
    return (
      <>
        {amount}
        {currency || 'CC'}
      </>
    );
  } else {
    return <>...</>;
  }
};

const PaymentAmountDisplay: React.FC<{
  amount: PaymentAmount;
}> = ({ amount }) => <AmountDisplay amount={amount.amount} currency={amount.currency} />;

function sumPaymentAmounts(pqs: PaymentAmount[], coinPrice?: Decimal): Decimal | undefined {
  if (!coinPrice) {
    return;
  }

  return pqs.reduce((sum: Decimal | undefined, pq) => {
    const units = new Decimal(pq.amount);
    return sum?.plus(pq.currency === 'USD' ? units.div(coinPrice) : units);
  }, new Decimal(0.0));
}

function renderAmount(q?: Decimal): string {
  if (q) {
    return q.toPrecision(10);
  } else {
    return '...';
  }
}

const PaymentAmountTotalDisplay: React.FC<{
  amounts: PaymentAmount[];
  coinPrice?: Decimal;
}> = ({ amounts, coinPrice }) => (
  <AmountDisplay amount={renderAmount(sumPaymentAmounts(amounts, coinPrice))} />
);

export { AmountDisplay, PaymentAmountDisplay, PaymentAmountTotalDisplay };
