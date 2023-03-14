import * as React from 'react';
import { AmountDisplay, useInterval } from 'common-frontend';
import { Decimal } from 'decimal.js';
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

  const [walletBalance, setWalletBalance] = useState<WalletBalance>({ totalCC: new Decimal(0) });

  const fetchBalance = useCallback(async () => {
    const balance = await walletClient.getBalance();
    setWalletBalance(balance);
  }, [walletClient]);

  useInterval(fetchBalance, 1000);

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
          <Typography className="payment-current-user">
            <b>
              <CurrentUser />
            </b>
          </Typography>
          <Typography className="available-balance">
            Total Available Balance:{' '}
            <AmountDisplay amount={walletBalance.totalCC.toString()} currency="CC" /> /{' '}
            <AmountDisplay
              amount={walletBalance.totalCC.mul(coinPrice.toString()).toString()}
              currency="USD"
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
