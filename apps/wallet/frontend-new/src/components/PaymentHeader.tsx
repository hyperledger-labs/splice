import * as React from 'react';
import { AmountDisplay } from 'common-frontend';

import { Stack, Toolbar, Typography } from '@mui/material';

const PaymentHeader: React.FC = () => {
  const cns = 'alice.cns';
  const balanceCC = 150;
  const balanceUsd = 1800;
  return (
    <Toolbar sx={{ padding: 2 }}>
      <Typography variant="h5" autoCapitalize="characters" flex={'1'}>
        Canton Coin Wallet
      </Typography>
      <Stack spacing={2} alignItems="center">
        <Typography>
          <b>{cns}</b>
        </Typography>
        <Typography>
          Total Available Balance: <AmountDisplay amount={balanceCC.toString()} currency="CC" /> /{' '}
          <AmountDisplay amount={balanceUsd.toString()} currency="USD" />
        </Typography>
      </Stack>
      {/*Empty element to align the other two to left and center*/}
      <div style={{ flex: 1 }} />
    </Toolbar>
  );
};

export default PaymentHeader;
