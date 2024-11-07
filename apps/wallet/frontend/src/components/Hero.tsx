// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { AmountDisplay, ErrorDisplay, Loading } from 'common-frontend';

import { Box, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { useBalance } from '../hooks';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import { useWalletConfig } from '../utils/config';

const Hero: React.FC = () => {
  const config = useWalletConfig();
  const balanceQuery = useBalance();
  const amuletPriceQuery = useAmuletPrice();

  const isLoading = balanceQuery.isLoading || amuletPriceQuery.isLoading;
  const isError = balanceQuery.isError || amuletPriceQuery.isError;

  return (
    <Stack mt={4} mb={4} spacing={4} direction="row" justifyContent="space-between">
      <Stack direction="column" spacing={1}>
        {isLoading ? (
          <Loading />
        ) : isError ? (
          <ErrorDisplay message={'Error while fetching balance and amulet price.'} />
        ) : (
          <Box>
            <Typography variant="h6">Total Available Balance</Typography>
            <Typography id="wallet-balance-amulet" variant="h4">
              <AmountDisplay amount={balanceQuery.data.availableCC} currency="AmuletUnit" />
            </Typography>
            <Typography id="wallet-balance-usd" variant="caption" style={{ marginRight: 12 }}>
              <AmountDisplay
                amount={balanceQuery.data.availableCC}
                currency="AmuletUnit"
                convert="CCtoUSD"
                amuletPrice={amuletPriceQuery.data}
              />
            </Typography>
            <Typography variant="caption">
              Reflects unlocked {config.spliceInstanceNames.amuletName}, rewards earned and holding
              fees
            </Typography>
          </Box>
        )}
      </Stack>
    </Stack>
  );
};
export default Hero;
