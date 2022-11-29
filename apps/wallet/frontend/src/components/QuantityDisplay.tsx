import React from 'react';

import { PaymentQuantity } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

const QuantityDisplay: React.FC<{
  quantity: string;
  currency?: string;
}> = ({ quantity, currency }) => (
  <>
    {quantity}
    {currency || 'CC'}
  </>
);

const PaymentQuantityDisplay: React.FC<{
  quantity: PaymentQuantity;
}> = ({ quantity }) => (
  <QuantityDisplay quantity={quantity.quantity} currency={quantity.currency} />
);

export { QuantityDisplay, PaymentQuantityDisplay };
