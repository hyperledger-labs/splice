import * as React from 'react';
import { AmountDisplay, ErrorDisplay, Loading } from 'common-frontend';
import { useCoinPrice } from 'common-frontend/scan-api';

import { Box, Divider, Stack, Toolbar, Typography } from '@mui/material';

import { useBalance } from '../hooks';
import CurrentUser from './CurrentUser';
import { LogoutButton } from './LogoutButton';

const PaymentHeader: React.FC = () => {
  const coinPriceQuery = useCoinPrice();
  const balanceQuery = useBalance();

  const isLoading = coinPriceQuery.isLoading || balanceQuery.isLoading;
  const isError = coinPriceQuery.isError || balanceQuery.isError;

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
          {isLoading ? (
            <Loading />
          ) : isError ? (
            <ErrorDisplay message={'Error while fetching coin price and balance'} />
          ) : (
            <Typography className="available-balance">
              Total Available Balance:{' '}
              <AmountDisplay amount={balanceQuery.data.availableCC} currency="CC" /> /{' '}
              <AmountDisplay
                amount={balanceQuery.data.availableCC}
                currency="CC"
                convert="CCtoUSD"
                coinPrice={coinPriceQuery.data}
              />
            </Typography>
          )}
        </Stack>
        {/*Empty element to align the other two to left and center*/}
        <div style={{ flex: 1 }} />
      </Toolbar>
    </Box>
  );
};

export default PaymentHeader;
