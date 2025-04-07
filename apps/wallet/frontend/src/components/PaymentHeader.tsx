// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { AmountDisplay, ErrorDisplay, Loading } from '@lfdecentralizedtrust/splice-common-frontend';

import { Box, Divider, Stack, Toolbar, Typography } from '@mui/material';

import { useBalance } from '../hooks';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import { useWalletConfig } from '../utils/config';
import CurrentUser from './CurrentUser';
import { LogoutButton } from './LogoutButton';

const PaymentHeader: React.FC = () => {
  const config = useWalletConfig();
  const amuletPriceQuery = useAmuletPrice();
  const balanceQuery = useBalance();

  const isLoading = amuletPriceQuery.isLoading || balanceQuery.isLoading;
  const isError = amuletPriceQuery.isError || balanceQuery.isError;

  return (
    <Box bgcolor="colors.neutral.20">
      <Toolbar sx={{ padding: 2 }}>
        <Typography variant="h5" autoCapitalize="characters" flex={'1'}>
          {config.spliceInstanceNames.amuletName} Wallet
        </Typography>
        <Stack spacing={2} alignItems="center">
          <Stack spacing={2} direction="row">
            <span className="payment-current-user">
              <CurrentUser />
            </span>
            <Divider flexItem orientation="vertical" />
            <LogoutButton />
          </Stack>
          {isLoading ? (
            <Loading />
          ) : isError ? (
            <ErrorDisplay message={'Error while fetching amulet price and balance'} />
          ) : (
            <Typography className="available-balance">
              Total Available Balance:{' '}
              <AmountDisplay amount={balanceQuery.data.availableCC} currency="AmuletUnit" /> /{' '}
              <AmountDisplay
                amount={balanceQuery.data.availableCC}
                currency="AmuletUnit"
                convert="CCtoUSD"
                amuletPrice={amuletPriceQuery.data}
              />
            </Typography>
          )}
        </Stack>
        {/*Empty element to align the other two to left and center*/}
        <div style={{ flex: 1 }} />
      </Toolbar>
    </Box>
  );
};

export default PaymentHeader;
