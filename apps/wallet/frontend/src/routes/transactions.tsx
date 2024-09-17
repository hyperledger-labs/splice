// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box } from '@mui/material';

import DevNetOnly from '../components/DevNetOnly';
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
