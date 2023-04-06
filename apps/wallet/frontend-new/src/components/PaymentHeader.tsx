import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, useInterval } from 'common-frontend';
import { useCallback, useState } from 'react';

import { Box, Stack, Toolbar, Typography } from '@mui/material';

import { useCoinPrice } from '../contexts/CoinPriceContext';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { WalletBalance } from '../models/models';
import CurrentUser from './CurrentUser';
import Loading from './Loading';

const PaymentHeader: React.FC = () => {
  const walletClient = useWalletClient();

  const coinPrice = useCoinPrice();

  const [walletBalance, setWalletBalance] = useState<WalletBalance>({
    availableCC: new BigNumber(0),
  });

  const fetchBalance = useCallback(async () => {
    const balance = await walletClient.getBalance();
    setWalletBalance(balance);
  }, [walletClient]);

  useInterval(fetchBalance);

  if (!coinPrice) {
    return <Loading />;
  }

  return (
    <Box bgcolor="colors.neutral.20">
      <Toolbar sx={{ padding: 2 }}>
        <Typography variant="h5" autoCapitalize="characters" flex={'1'}>
          Canton Coin Wallet
        </Typography>
        <Stack spacing={2} alignItems="center">
          <span className="payment-current-user">
            <CurrentUser />
          </span>
          <Typography className="available-balance">
            Total Available Balance:{' '}
            <AmountDisplay amount={walletBalance.availableCC} currency="CC" /> /{' '}
            <AmountDisplay
              amount={walletBalance.availableCC}
              currency="CC"
              convert="CCtoUSD"
              coinPrice={coinPrice}
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
