import { Contract, sameContracts, useDirectoryClient, useInterval } from 'common-frontend';
import { ErrorBoundary } from 'common-frontend';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useCallback, useEffect, useState } from 'react';

import { AppBar, Box, CssBaseline, Toolbar, Typography } from '@mui/material';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import './App.css';
import DirectoryEntries from './DirectoryEntries';
import Home from './Home';
import Login from './Login';
import { useScanClient } from './contexts/ScanServiceContext';

const App: React.FC = () => {
  const [directoryEntries, setDirectoryEntries] = useState<Contract<DirectoryEntry>[]>([]);
  const dirEntries = new DirectoryEntries(directoryEntries);
  const directoryClient = useDirectoryClient();
  const scanClient = useScanClient();

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
  );
};

export default App;
