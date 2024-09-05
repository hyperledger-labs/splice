// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Header, useUserState } from 'common-frontend';

import { Logout } from '@mui/icons-material';
import { Box, Button, Divider, Stack } from '@mui/material';
import Container from '@mui/material/Container';
import Link from '@mui/material/Link';

import { useSvConfig } from '../utils';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  const config = useSvConfig();
  const { logout } = useUserState();

  return (
    <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" minHeight="100vh">
      <Container maxWidth="xl">
        <Header
          title="Super Validator Operations"
          navLinks={[
            { name: 'Information', path: 'dso' },
            { name: 'Validator Onboarding', path: 'validator-onboarding' },
            { name: `${config.spliceInstanceNames.amuletName} Price`, path: 'cc-price' },
            { name: 'Delegate Election', path: 'delegate' },
            { name: 'Governance', path: 'votes' },
          ]}
        >
          <Stack direction="row" alignItems="center" spacing={1}>
            <Divider key="divider" orientation="vertical" variant="middle" flexItem />
            <Button key="button" id="logout-button" onClick={logout} color="inherit">
              <Stack direction="row" alignItems="center">
                <Logout />
                <Link color="inherit" textTransform="none">
                  Logout
                </Link>
              </Stack>
            </Button>
          </Stack>
        </Header>
      </Container>

      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg">{props.children}</Container>
      </Box>
    </Box>
  );
};
export default Layout;
