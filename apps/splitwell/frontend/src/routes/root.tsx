// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AnsEntry as AnsEntryComponent,
  useUserState,
  usePrimaryParty,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Outlet } from 'react-router';

import { AppBar, Box, Button, CssBaseline, Toolbar, Typography } from '@mui/material';

import './root.css';

const Root: React.FC = () => {
  const { logout } = useUserState();
  const primaryPartyId = usePrimaryParty().data;

  return (
    <Box>
      <CssBaseline />
      <AppBar position="static" sx={{ marginBottom: 5 }}>
        <Toolbar>
          <Typography variant="h6" sx={{ flexGrow: 1 }} id="app-title">
            Splitwell
            {primaryPartyId && (
              <div id="logged-in-user" data-selenium-text={primaryPartyId}>
                <AnsEntryComponent partyId={primaryPartyId} />
              </div>
            )}
          </Typography>
          <Button color="inherit" onClick={logout} id="logout-button">
            Log Out
          </Button>
        </Toolbar>
      </AppBar>
      <Outlet />
    </Box>
  );
};

export default Root;
