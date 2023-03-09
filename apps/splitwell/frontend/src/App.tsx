import { DirectoryEntry as DirectoryEntryComponent, Login, useUserState } from 'common-frontend';
import { ErrorBoundary } from 'common-frontend';
import { useScanClient } from 'common-frontend/lib/contexts/ScanServiceContext';
import { useEffect, useState } from 'react';

import { AppBar, Box, Button, CssBaseline, Toolbar, Typography } from '@mui/material';

import './App.css';
import Home from './Home';
import { config } from './utils/config';

const App: React.FC = () => {
  const scanClient = useScanClient();

  const [svc, setSvc] = useState<string | undefined>();
  useEffect(() => {
    const fetchSvc = async () => {
      const svcPartyId = await scanClient.getSvcPartyId();
      setSvc(svcPartyId);
    };
    fetchSvc();
  }, [scanClient]);

  const { isAuthenticated, logout, userId, userAccessToken, primaryPartyId } = useUserState();

  return (
    <ErrorBoundary>
      <Box>
        <CssBaseline />
        <AppBar position="static" sx={{ marginBottom: 5 }}>
          <Toolbar>
            <Typography variant="h6" sx={{ flexGrow: 1 }} id="app-title">
              CN Splitwell
              {primaryPartyId && (
                <div id="logged-in-user">
                  <DirectoryEntryComponent partyId={primaryPartyId} />
                </div>
              )}
            </Typography>
            {isAuthenticated && (
              <Button color="inherit" onClick={logout} id="logout-button">
                Log Out
              </Button>
            )}
          </Toolbar>
        </AppBar>
        {isAuthenticated && userId && userAccessToken ? (
          <Home userId={userId} svc={svc} ledgerApiToken={userAccessToken} />
        ) : (
          <Login
            title={'Splitwell Log In'}
            authConfig={config.auth}
            testAuthConfig={config.testAuth}
          />
        )}
      </Box>
    </ErrorBoundary>
  );
};

export default App;
