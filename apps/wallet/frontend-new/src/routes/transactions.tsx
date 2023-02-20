import * as React from 'react';

import TransactionHistory from '../components/TransactionHistory';
import { TransferOffers } from '../components/TransferOffers';
import { Transaction } from '../models/models';

const Transactions: React.FC = () => {
  const txs: Transaction[] = [
    {
      action: 'Sent',
      recipientId: 'Thomas Edison',
      providerId: 'Splitwise',
      totalCCAmount: '-31',
      totalUSDAmount: '372',
      conversionRate: '12',
      date: '01-01-2023 16:01:01',
    },
    {
      action: 'Received',
      recipientId: 'Multiple Recipients',
      providerId: 'BORF',
      totalCCAmount: '93',
      totalUSDAmount: '1,116',
      conversionRate: '12',
      date: '01-02-2023 06:01:01',
    },
  ];

  return (
    <>
      <TransferOffers />
      <TransactionHistory transactions={txs} />
    </>
  );
};
export default Transactions;
