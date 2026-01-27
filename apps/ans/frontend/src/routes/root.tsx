// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ErrorBoundary,
  Header,
  Loading,
  PartyId,
  useUserState,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Outlet } from 'react-router';

import { Box, Button, Stack } from '@mui/material';

import { usePrimaryParty } from '../hooks/queries/usePrimaryParty';
import { useAnsConfig } from '../utils';

const Root: React.FC = () => {
  const config = useAnsConfig();
  const { logout } = useUserState();
  const primaryPartyId = usePrimaryParty();

  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <Box bgcolor="colors.neutral.20">
          <Header title={config.spliceInstanceNames.nameServiceName}>
            <Stack direction="row" alignItems="center" spacing={1}>
              {primaryPartyId && ( // Using a DirectoryEntry here seems a bit circular
                <PartyId partyId={primaryPartyId} id="logged-in-user" />
              )}
              <Button key="logout" color="inherit" onClick={logout} id="logout-button">
                Log Out
              </Button>
            </Stack>
          </Header>
        </Box>
        {primaryPartyId ? <Outlet /> : <Loading />}
      </Box>
    </ErrorBoundary>
  );
};

export default Root;
