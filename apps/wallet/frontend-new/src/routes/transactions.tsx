import * as React from 'react';

import TransactionHistory from '../components/TransactionHistory';
import { TransferOffers } from '../components/TransferOffers';

const Transactions: React.FC = () => {
  return (
    <>
      <TransferOffers />
      <TransactionHistory />
    </>
  );
};
export default Transactions;
