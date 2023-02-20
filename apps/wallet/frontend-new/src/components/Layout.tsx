import * as React from 'react';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import { WalletBalance } from '../models/models';
import Header from './Header';
import Hero from './Hero';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  const currentUser = 'alice.cns';
  const balance: WalletBalance = { totalCC: '150', totalUSD: '300' };
  const upcomingRewardsBalance: WalletBalance = { totalCC: '210', totalUSD: '420' };

  return (
    <Box display="flex" flexDirection="column" minHeight="100vh">
      <Container maxWidth="xl">
        <Header currentUser={currentUser} />
      </Container>

      <Container maxWidth="md">
        <Hero balance={balance} upcomingRewardsBalance={upcomingRewardsBalance} />
      </Container>

      <Box sx={{ backgroundColor: '#2c394f', flex: 1 }}>
        <Container maxWidth="md">{props.children}</Container>
      </Box>
    </Box>
  );
};
export default Layout;
