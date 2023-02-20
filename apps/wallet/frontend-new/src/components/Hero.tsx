import * as React from 'react';

import { Divider, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { WalletBalance } from '../models/models';
import AmountDisplay from './AmountDisplay';

interface HeroProps {
  balance: WalletBalance;
  upcomingRewardsBalance: WalletBalance;
}

const Hero: React.FC<HeroProps> = props => {
  return (
    <Stack mt={4} mb={4} spacing={4} direction="row" justifyContent="space-between">
      <Stack direction="column" spacing={1}>
        <Typography variant="h6">Total Available Balance</Typography>
        <Typography variant="h5">
          <AmountDisplay amount={props.balance.totalCC} /> /{' '}
          <AmountDisplay amount={props.balance.totalUSD} currency="USD" />
        </Typography>
        <Typography variant="caption">
          Reflects unlocked coin, rewards earned and holding fees
        </Typography>
      </Stack>
      <Divider orientation="vertical" variant="middle" />
      <Stack direction="column" spacing={1}>
        <Typography variant="h6">Your Upcoming Rewards</Typography>
        <Typography variant="h5">
          <AmountDisplay amount={props.upcomingRewardsBalance.totalCC} /> /{' '}
          <AmountDisplay amount={props.upcomingRewardsBalance.totalUSD} currency="USD" />
        </Typography>
        <Typography variant="caption">
          Reflects amount that will be added to your balance within 3 minutes
        </Typography>
      </Stack>
    </Stack>
  );
};
export default Hero;
