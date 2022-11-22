import React from 'react';

import { PaymentQuantity } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

const QuantityDisplay: React.FC<{
  quantity: string;
}> = ({ quantity }) => <>{quantity}CC</>;

const PaymentQuantityDisplay: React.FC<{
  quantity: PaymentQuantity;
}> = ({ quantity }) => (
  <>
    {quantity.quantity}
    {quantity.currency}
  </>
);

export { QuantityDisplay, PaymentQuantityDisplay };
