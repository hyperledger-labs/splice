import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay } from 'common-frontend';

import { Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { WalletBalance } from '../models/models';

interface HeroProps {
  balance: WalletBalance;
  coinPrice: BigNumber;
}

const Hero: React.FC<HeroProps> = props => {
  return (
    <Stack mt={4} mb={4} spacing={4} direction="row" justifyContent="space-between">
      <Stack direction="column" spacing={1}>
        <Typography variant="h6">Total Available Balance</Typography>
        <Typography id="wallet-balance-cc" variant="h4">
          <AmountDisplay amount={props.balance.availableCC} currency="CC" />
        </Typography>
        <Typography id="wallet-balance-usd" variant="caption">
          <AmountDisplay
            amount={props.balance.availableCC}
            currency="CC"
            convert="CCtoUSD"
            coinPrice={props.coinPrice}
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
