import * as React from 'react';
import { AmountDisplay, Loading } from 'common-frontend';

import { Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { useBalance } from '../hooks/useBalance';
import { useCoinPrice } from '../hooks/useCoinPrice';

const Hero: React.FC = () => {
  const balanceQuery = useBalance();
  const coinPriceQuery = useCoinPrice();

  // TODO(#4139) Implement design for loading and error states
  if (balanceQuery.isLoading || coinPriceQuery.isLoading) {
    return <Loading />;
  }

  if (balanceQuery.isError || coinPriceQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  return (
    <Stack mt={4} mb={4} spacing={4} direction="row" justifyContent="space-between">
      <Stack direction="column" spacing={1}>
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
      </Stack>
    </Stack>
  );
};
export default Hero;
