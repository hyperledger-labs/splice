import {
  DirectoryEntry as DirectoryEntryComponent,
  ErrorBoundary,
  useUserState,
  usePrimaryParty,
} from 'common-frontend';
import { Outlet } from 'react-router-dom';

import { AppBar, Box, Button, CssBaseline, Toolbar, Typography } from '@mui/material';

import './root.css';

const Root: React.FC = () => {
  const { logout } = useUserState();
  const primaryPartyId = usePrimaryParty().data;

  return (
    <ErrorBoundary>
      <Box>
        <CssBaseline />
        <AppBar position="static" sx={{ marginBottom: 5 }}>
          <Toolbar>
            <Typography variant="h6" sx={{ flexGrow: 1 }} id="app-title">
              CN Splitwell
              {primaryPartyId && (
                <div id="logged-in-user" data-selenium-text={primaryPartyId}>
                  <DirectoryEntryComponent partyId={primaryPartyId} />
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
    </ErrorBoundary>
  );
};

export default Root;
