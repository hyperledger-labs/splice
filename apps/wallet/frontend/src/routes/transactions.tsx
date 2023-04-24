import * as React from 'react';
import DevNetOnly from 'common-frontend/lib/components/DevNetOnly';

import { Box } from '@mui/material';

import Tap from '../components/Tap';
import TransactionHistory from '../components/TransactionHistory';
import { TransferOffers } from '../components/TransferOffers';

const Transactions: React.FC = () => {
  return (
    <Box marginTop={4}>
      <DevNetOnly>
        <Tap />
      </DevNetOnly>
      <TransferOffers />
      <TransactionHistory />
    </Box>
  );
};
export default Transactions;
