// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Stack, Typography } from '@mui/material';

interface NetworkBannerProps {
  networkName: string;
}

const knownColors = ['mainnet', 'testnet', 'devnet', 'scratchnet', 'localnet'];

const NetworkBanner: React.FC<NetworkBannerProps> = ({ networkName }) => {
  const normalizedNetworkName = networkName.toLowerCase();

  if (normalizedNetworkName === 'mainnet') {
    return null;
  }

  const backgroundColor = knownColors.includes(normalizedNetworkName)
    ? `colors.${normalizedNetworkName}`
    : 'colors.neutral.30';

  return (
    <Stack
      direction="row"
      alignItems="center"
      justifyContent="center"
      sx={{
        backgroundColor,
        color: 'black',
        height: '50px',
        width: '100%',
      }}
    >
      <Typography id="network-instance-name" data-testid="network-instance-name" variant="h6">
        <b>You are on {networkName}</b>
      </Typography>
    </Stack>
  );
};

export default NetworkBanner;
