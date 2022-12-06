import { Decimal } from 'decimal.js';
import React from 'react';

import { PaymentQuantity } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

const QuantityDisplay: React.FC<{
  quantity?: string;
  currency?: string;
}> = ({ quantity, currency }) => {
  if (quantity) {
    return (
      <>
        {quantity}
        {currency || 'CC'}
      </>
    );
  } else {
    return <>...</>;
  }
};

const PaymentQuantityDisplay: React.FC<{
  quantity: PaymentQuantity;
}> = ({ quantity }) => (
  <QuantityDisplay quantity={quantity.quantity} currency={quantity.currency} />
);

function sumPaymentQuantities(pqs: PaymentQuantity[], coinPrice?: Decimal): Decimal | undefined {
  if (!coinPrice) {
    return;
  }

  return pqs.reduce((sum: Decimal | undefined, pq) => {
    const units = new Decimal(pq.quantity);
    return sum?.plus(pq.currency === 'USD' ? units.div(coinPrice) : units);
  }, new Decimal(0.0));
}

function renderQuantity(q?: Decimal): string {
  if (q) {
    return q.toPrecision(10);
  } else {
    return '...';
  }
}

const PaymentQuantityTotalDisplay: React.FC<{
  quantities: PaymentQuantity[];
  coinPrice?: Decimal;
}> = ({ quantities, coinPrice }) => (
  <QuantityDisplay quantity={renderQuantity(sumPaymentQuantities(quantities, coinPrice))} />
);

export { QuantityDisplay, PaymentQuantityDisplay, PaymentQuantityTotalDisplay };
