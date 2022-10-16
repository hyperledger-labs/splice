import { sameContracts, useInterval } from 'common-frontend';
import { ErrorBoundary } from 'common-frontend';
import { DirectoryServicePromiseClient } from 'common-protobuf/com/daml/network/directory/v0/directory_service_grpc_web_pb';
import { ScanServicePromiseClient } from 'common-protobuf/com/daml/network/scan/v0/scan_service_grpc_web_pb';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { AppBar, Box, CssBaseline, Toolbar, Typography } from '@mui/material';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import './App.css';
import { Contract } from './Contract';
import DirectoryEntries from './DirectoryEntries';
import Home from './Home';
import Login from './Login';
import { SplitwiseClientProvider } from './SplitwiseServiceContext';

const App: React.FC = () => {
  const [directoryEntries, setDirectoryEntries] = useState<Contract<DirectoryEntry>[]>([]);
  const dirEntries = new DirectoryEntries(directoryEntries);
  const directoryClient = useMemo(
    () => new DirectoryServicePromiseClient('http://localhost:8084'),
    []
  );
  const scanClient = useMemo(() => new ScanServicePromiseClient('http://localhost:8083'), []);

  const fetchDirectoryEntries = useCallback(async () => {
    const newEntries = (await directoryClient.listEntries(new Empty(), undefined)).getEntriesList();
    const decoded = newEntries.map(c => Contract.decode(c, DirectoryEntry));
    setDirectoryEntries(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [setDirectoryEntries, directoryClient]);

  useInterval(fetchDirectoryEntries, 500);

  const [svc, setSvc] = useState<string | undefined>();
  useEffect(() => {
    const fetchSvc = async () => {
      const svc = await scanClient.getSvcPartyId(new Empty(), undefined);
      setSvc(svc.getSvcPartyId());
    };
    fetchSvc();
  }, [scanClient]);

  const [userId, setUserId] = useState<string | undefined>();

  return (
    <SplitwiseClientProvider url={process.env.REACT_APP_GRPC_URL || 'http://localhost:8082'}>
      <ErrorBoundary>
        <Box>
          <CssBaseline />
          <AppBar position="static" sx={{ marginBottom: 5 }}>
            <Toolbar>
              <Typography variant="h6">CN Splitwise</Typography>
            </Toolbar>
          </AppBar>
          {userId ? (
            <Home userId={userId} svc={svc} dirEntries={dirEntries} />
          ) : (
            <Login onLogin={setUserId} />
          )}
        </Box>
      </ErrorBoundary>
    </SplitwiseClientProvider>
  );
};

export default App;
