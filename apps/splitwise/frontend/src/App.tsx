import { Contract, sameContracts, useDirectoryClient, useInterval } from 'common-frontend';
import { ErrorBoundary } from 'common-frontend';
import { generateLedgerApiToken } from 'common-frontend/lib/contexts/LedgerApiContext';
import { useScanClient } from 'common-frontend/lib/contexts/ScanServiceContext';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useCallback, useEffect, useState } from 'react';

import { AppBar, Box, CssBaseline, Toolbar, Typography } from '@mui/material';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import './App.css';
import DirectoryEntries from './DirectoryEntries';
import Home from './Home';
import Login from './Login';

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
  const [ledgerApiToken, setLedgerApiToken] = useState<string | undefined>();
  useEffect(() => {
    const generateToken = async (userId: string | undefined) => {
      if (userId !== undefined) {
        const token = await generateLedgerApiToken(userId);
        setLedgerApiToken(token);
      }
    };
    generateToken(userId);
  }, [userId]);

  return (
    <ErrorBoundary>
      <Box>
        <CssBaseline />
        <AppBar position="static" sx={{ marginBottom: 5 }}>
          <Toolbar>
            <Typography variant="h6">CN Splitwise</Typography>
          </Toolbar>
        </AppBar>
        {userId && ledgerApiToken ? (
          <Home userId={userId} svc={svc} dirEntries={dirEntries} ledgerApiToken={ledgerApiToken} />
        ) : (
          <Login onLogin={setUserId} />
        )}
      </Box>
    </ErrorBoundary>
  );
};

export default App;
