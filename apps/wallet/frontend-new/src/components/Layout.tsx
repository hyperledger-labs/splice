import * as React from 'react';
import BigNumber from 'bignumber.js';
import { Contract, useScanClient } from 'common-frontend';
import { useInterval } from 'common-frontend/lib/utils/hooks';
import { Decimal } from 'decimal.js';
import { useCallback, useState } from 'react';
import { GetOpenAndIssuingMiningRoundsRequest } from 'scan-openapi';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import { OpenMiningRound } from '@daml.js/canton-coin-0.1.0/lib/CC/Round';

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
  const scanClient = useScanClient();

  const [walletBalance, setWalletBalance] = useState<WalletBalance>({ totalCC: '', totalUSD: '' });
  const [coinPrice, setCoinPrice] = useState<Decimal>(new Decimal(0));

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

  const fetchBalance = useCallback(async () => {
    const balResponse = await walletClient.getBalance();
    setWalletBalance(toWalletBalance(balResponse, coinPrice));
  }, [coinPrice, walletClient]);

  const fetchCoinPrice = useCallback(async () => {
    const request: GetOpenAndIssuingMiningRoundsRequest = {
      cachedOpenMiningRoundContractIds: [],
      cachedIssuingRoundContractIds: [],
    };
    const getOpenAndIssuingMiningRounds = await scanClient.getOpenAndIssuingMiningRounds(request);

    const omr = getOpenAndIssuingMiningRounds.openMiningRounds;
    const openOpenRounds = Object.values(omr)
      .map(mybCached => Contract.decodeOpenAPI(mybCached.contract!, OpenMiningRound))
      .filter(omr => Date.parse(omr.payload.opensAt) <= Date.now());
    if (openOpenRounds.length > 0) {
      const latestOpenRound = openOpenRounds.reduce((prevOmr, currentOmr) =>
        prevOmr.payload.round.number > currentOmr.payload.round.number ? prevOmr : currentOmr
      );
      const newCoinPrice = new Decimal(latestOpenRound.payload.coinPrice);
      setCoinPrice(newCoinPrice);
    }
  }, [scanClient]);

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
