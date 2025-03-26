// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Header } from '@lfdecentralizedtrust/splice-common-frontend';

import { Box, Divider, Stack } from '@mui/material';
import Container from '@mui/material/Container';

import { useWalletConfig } from '../utils/config';
import CurrentUser from './CurrentUser';
import FeaturedAppRight from './FeaturedAppRight';
import Hero from './Hero';
import LogoutButton from './LogoutButton';
import TransferPreapproval from './TransferPreapproval';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  const config = useWalletConfig();
  return (
    <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" minHeight="100vh">
      <Container maxWidth="xl">
        <Header
          title={config.spliceInstanceNames.amuletName + ' Wallet'}
          navLinks={[
            { name: 'Transactions', path: 'transactions' },
            { name: 'Transfer', path: 'transfer' },
            { name: 'Subscriptions', path: 'subscriptions' },
            { name: 'FAQs', path: 'faqs' },
          ]}
        >
          <Stack direction="row" alignItems="center" spacing={1} paddingLeft={1}>
            <CurrentUser key="current-user" />
            <FeaturedAppRight key="featured-app-right" />
            <TransferPreapproval key="transfer-preapproval" />
            <Divider key="divider" orientation="vertical" variant="middle" flexItem />
            <LogoutButton key="logout-button" />
          </Stack>
        </Header>
      </Container>

      <Container maxWidth="lg">
        <Hero />
      </Container>

      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg">{props.children}</Container>
      </Box>
    </Box>
  );
};
export default Layout;
