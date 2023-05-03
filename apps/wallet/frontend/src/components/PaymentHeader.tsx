import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, useInterval } from 'common-frontend';
import Loading from 'common-frontend/lib/components/Loading';
import { useCallback, useState } from 'react';

import { Box, Divider, Stack, Toolbar, Typography } from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { useCoinPrice } from '../hooks/useCoinPrice';
import { WalletBalance } from '../models/models';
import CurrentUser from './CurrentUser';
import { LogoutButton } from './LogoutButton';

const PaymentHeader: React.FC = () => {
  const walletClient = useWalletClient();

  const coinPriceQuery = useCoinPrice();

  const [walletBalance, setWalletBalance] = useState<WalletBalance>({
    availableCC: new BigNumber(0),
  });

  const fetchBalance = useCallback(async () => {
    const balance = await walletClient.getBalance();
    setWalletBalance(balance);
  }, [walletClient]);

  useInterval(fetchBalance);

  if (coinPriceQuery.isLoading) {
    return <Loading />;
  }

  return (
    <Box bgcolor="colors.neutral.20">
      <Toolbar sx={{ padding: 2 }}>
        <Typography variant="h5" autoCapitalize="characters" flex={'1'}>
          Canton Coin Wallet
        </Typography>
        <Stack spacing={2} alignItems="center">
          <Stack spacing={2} direction="row">
            <span className="payment-current-user">
              <CurrentUser />
            </span>
            <Divider flexItem orientation="vertical" />
            <LogoutButton />
          </Stack>
          <Typography className="available-balance">
            Total Available Balance:{' '}
            <AmountDisplay amount={walletBalance.availableCC} currency="CC" /> /{' '}
            <AmountDisplay
              amount={walletBalance.availableCC}
              currency="CC"
              convert="CCtoUSD"
              coinPrice={coinPriceQuery.data}
            />
          </Typography>
        </Stack>
        {/*Empty element to align the other two to left and center*/}
        <div style={{ flex: 1 }} />
      </Toolbar>
    </Box>
  );
};

export default PaymentHeader;
