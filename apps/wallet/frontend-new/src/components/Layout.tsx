import * as React from 'react';
import BigNumber from 'bignumber.js';
import { useDirectoryClient, useUserState } from 'common-frontend';
import { useInterval } from 'common-frontend/lib/utils/hooks';
import { useCallback, useEffect, useState } from 'react';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import { useCoinPrice } from '../contexts/CoinPriceContext';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { GetBalanceResponse, WalletBalance } from '../models/models';
import Header from './Header';
import Hero from './Hero';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  const walletClient = useWalletClient();
  const directoryClient = useDirectoryClient();

  const [currentUser, setCurrentUser] = useState<string | undefined>(undefined);
  const [walletBalance, setWalletBalance] = useState<WalletBalance>({ totalCC: '', totalUSD: '' });
  const { primaryPartyId } = useUserState();

  const toWalletBalance = (b: GetBalanceResponse, coinPrice: BigNumber): WalletBalance => {
    const locked = new BigNumber(b.effectiveLockedQty);
    const unlocked = new BigNumber(b.effectiveUnlockedQty);
    const fees = new BigNumber(b.totalHoldingFees);
    const totalCC = locked.plus(unlocked).plus(fees).toString();
    return {
      totalCC: totalCC,
      totalUSD: coinPrice.times(totalCC).toString(),
    };
  };

  const coinPrice = useCoinPrice();

  const fetchBalance = useCallback(async () => {
    if (coinPrice) {
      const balResponse = await walletClient.getBalance();
      setWalletBalance(toWalletBalance(balResponse, coinPrice));
    }
  }, [coinPrice, walletClient]);

  useEffect(() => {
    const fetchEntry = async (partyId: string) => {
      const entry = await directoryClient.lookupEntryByParty(partyId);
      if (entry !== undefined) {
        setCurrentUser(entry.name);
      }
    };
    if (primaryPartyId !== undefined) {
      fetchEntry(primaryPartyId);
    }
  }, [primaryPartyId, directoryClient]);

  // refresh data every second
  useInterval(fetchBalance, 1000);

  return (
    <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" minHeight="100vh">
      <Container maxWidth="xl">
        <Header currentUser={currentUser ?? ''} />
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
