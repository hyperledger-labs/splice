import * as React from 'react';
import BigNumber from 'bignumber.js';
import { useInterval } from 'common-frontend/lib/utils/hooks';
import { useCallback, useState } from 'react';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import { useCoinPrice } from '../contexts/CoinPriceContext';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { WalletBalance } from '../models/models';
import Header from './Header';
import Hero from './Hero';
import Loading from './Loading';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  const walletClient = useWalletClient();

  const [walletBalance, setWalletBalance] = useState<WalletBalance>({
    availableCC: new BigNumber(0),
  });

  const coinPrice = useCoinPrice();

  const fetchBalance = useCallback(async () => {
    const balance = await walletClient.getBalance();
    setWalletBalance(balance);
  }, [walletClient]);

  // refresh data every second
  useInterval(fetchBalance, 1000);

  if (!coinPrice) {
    return <Loading />;
  }

  return (
    <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" minHeight="100vh">
      <Container maxWidth="xl">
        <Header />
      </Container>

      <Container maxWidth="md">
        <Hero balance={walletBalance} coinPrice={coinPrice} />
      </Container>

      <Box bgcolor="colors.neutral.25" sx={{ flex: 1 }}>
        <Container maxWidth="md">{props.children}</Container>
      </Box>
    </Box>
  );
};
export default Layout;
