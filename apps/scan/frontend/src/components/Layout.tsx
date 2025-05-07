// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Header } from '@lfdecentralizedtrust/splice-common-frontend';
import { useBackfillingStatus } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';

import { Alert, Box, Stack } from '@mui/material';
import Container from '@mui/material/Container';

import { useScanConfig } from '../utils';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  const config = useScanConfig();
  const backfillingStatus = useBackfillingStatus();
  return (
    <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" minHeight="100vh">
      <Container maxWidth="xl">
        <Header
          title={config.spliceInstanceNames.amuletName + ' Scan'}
          navLinks={[
            { name: `${config.spliceInstanceNames.amuletName} Activity`, path: '/' },
            { name: `${config.spliceInstanceNames.amuletName} Price`, path: '/amulet-price-votes' },
            { name: 'Network Info', path: '/dso' },
            { name: 'Governance', path: '/governance' },
            { name: 'Validators', path: '/validator-licenses' },
          ]}
        />
        {backfillingStatus.data === false && (
          <Stack
            direction="row"
            alignItems="center"
            justifyContent="center"
            sx={{
              margin: '2px',
              height: '50px',
              width: '100%',
            }}
          >
            <Alert
              variant="filled"
              severity={'warning'}
              id={'backfilling-alert'}
              data-testid={'backfilling-alert'}
            >
              This scan instance is currently processing historical data in the background.
              Historical information such as recent activity or past votes will be incomplete until
              this processing finishes.
            </Alert>
          </Stack>
        )}
      </Container>

      {props.children}
    </Box>
  );
};
export default Layout;
