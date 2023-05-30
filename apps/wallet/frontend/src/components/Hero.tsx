import * as React from 'react';
import { AmountDisplay, ErrorDisplay, Loading } from 'common-frontend';
import { useCoinPrice } from 'common-frontend/scan-api';

import { Box, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { useBalance } from '../hooks';

const Hero: React.FC = () => {
  const balanceQuery = useBalance();
  const coinPriceQuery = useCoinPrice();

  const isLoading = balanceQuery.isLoading || coinPriceQuery.isLoading;
  const isError = balanceQuery.isError || coinPriceQuery.isError;

  return (
    <Stack mt={4} mb={4} spacing={4} direction="row" justifyContent="space-between">
      <Stack direction="column" spacing={1}>
        {isLoading ? (
          <Loading />
        ) : isError ? (
          <ErrorDisplay message={'Error while fetching balance and coin price.'} />
        ) : (
          <Box>
            <Typography variant="h6">Total Available Balance</Typography>
            <Typography id="wallet-balance-cc" variant="h4">
              <AmountDisplay amount={balanceQuery.data.availableCC} currency="CC" />
            </Typography>
            <Typography id="wallet-balance-usd" variant="caption">
              <AmountDisplay
                amount={balanceQuery.data.availableCC}
                currency="CC"
                convert="CCtoUSD"
                coinPrice={coinPriceQuery.data}
              />
            </Typography>
            <Typography variant="caption">
              Reflects unlocked coin, rewards earned and holding fees
            </Typography>
          </Box>
        )}
      </Stack>
    </Stack>
  );
};
export default Hero;
