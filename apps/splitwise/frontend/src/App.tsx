import {
  Contract,
  DirectoryEntry as DirectoryEntryComponent,
  Login,
  sameContracts,
  useDirectoryClient,
  useInterval,
  useUserState,
} from 'common-frontend';
import { ErrorBoundary } from 'common-frontend';
import { useScanClient } from 'common-frontend/lib/contexts/ScanServiceContext';
import { ListEntriesRequest } from 'common-protobuf/com/daml/network/directory/v0/directory_service_pb';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useCallback, useEffect, useState } from 'react';

import { AppBar, Box, Button, CssBaseline, Toolbar, Typography } from '@mui/material';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import './App.css';
import DirectoryEntries from './DirectoryEntries';
import Home from './Home';
import { config } from './utils/config';

const App: React.FC = () => {
  const [directoryEntries, setDirectoryEntries] = useState<Contract<DirectoryEntry>[]>([]);
  const dirEntries = new DirectoryEntries(directoryEntries);
  const directoryClient = useDirectoryClient();
  const scanClient = useScanClient();

  const fetchDirectoryEntries = useCallback(async () => {
    const req = new ListEntriesRequest();
    req.setNamePrefix('');
    req.setPageSize(50);
    const newEntries = (await directoryClient.listEntries(req, undefined)).getEntriesList();
    const decoded = newEntries.map(c => Contract.decode(c, DirectoryEntry));
    setDirectoryEntries(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [setDirectoryEntries, directoryClient]);

  // TODO(M3-08): use prefix-list for auto-completion, and per-party invites - then get rid of this polling
  useInterval(fetchDirectoryEntries, 500);

  const [svc, setSvc] = useState<string | undefined>();
  useEffect(() => {
    const fetchSvc = async () => {
      const svc = await scanClient.getSvcPartyId(new Empty(), undefined);
      setSvc(svc.getSvcPartyId());
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
              CN Splitwise
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
          <Home
            userId={userId}
            svc={svc}
            dirEntries={dirEntries}
            ledgerApiToken={userAccessToken}
          />
        ) : (
          <Login
            title={'Splitwise Log In'}
            authConfig={config.auth}
            testAuthConfig={config.testAuth}
          />
        )}
      </Box>
    </ErrorBoundary>
  );
};

export default App;
