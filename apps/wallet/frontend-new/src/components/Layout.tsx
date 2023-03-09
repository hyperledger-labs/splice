import * as React from 'react';
import BigNumber from 'bignumber.js';
import { useScanClient } from 'common-frontend';
import { useInterval } from 'common-frontend/lib/utils/hooks';
import { Decimal } from 'decimal.js';
import { useCallback, useState } from 'react';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { GetBalanceResponse, WalletBalance } from '../models/models';
import Header from './Header';
import Hero from './Hero';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  const currentUser = 'alice.cns';

  const walletClient = useWalletClient();

  const [walletBalance, setWalletBalance] = useState<WalletBalance>({ totalCC: '', totalUSD: '' });

  const toWalletBalance = (b: GetBalanceResponse, coinPrice: Decimal): WalletBalance => {
    const locked = new BigNumber(b.effectiveLockedQty);
    const unlocked = new BigNumber(b.effectiveUnlockedQty);
    const fees = new BigNumber(b.totalHoldingFees);
    const totalCC = locked.plus(unlocked).plus(fees).toString();
    return {
      totalCC: totalCC,
      totalUSD: coinPrice.times(totalCC).toString(),
    };
  };

  // TODO (#3332): remove and fetch from context once it's refactored
  const scanClient = useScanClient();
  const [coinPrice, setCoinPrice] = useState<Decimal>(new Decimal(0));
  const fetchCoinPrice = useCallback(async () => {
    const coinPrice = await scanClient.getCoinPrice();
    // avoid unnecessary re-renders everytime the coin price is fetched but does not change.
    setCoinPrice(prevCoinPrice => (prevCoinPrice?.equals(coinPrice) ? prevCoinPrice : coinPrice));
  }, [scanClient]);

  const fetchBalance = useCallback(async () => {
    const balResponse = await walletClient.getBalance();
    setWalletBalance(toWalletBalance(balResponse, coinPrice));
  }, [coinPrice, walletClient]);

  useInterval(fetchBalance, 1000);
  useInterval(fetchCoinPrice, 1000);

  return (
    <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" minHeight="100vh">
      <Container maxWidth="xl">
        <Header currentUser={currentUser} />
      </Container>

      <Container maxWidth="md">
        <Hero balance={walletBalance} />
      </Container>

      <Box bgcolor="colors.neutral.25" sx={{ flex: 1 }}>
        <Container maxWidth="md">{props.children}</Container>
      </Box>
    </Box>
  );
};
export default Layout;
