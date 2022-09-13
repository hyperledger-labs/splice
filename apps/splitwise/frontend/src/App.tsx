import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { AppBar, Box, Container, CssBaseline, Stack, Toolbar, Typography } from '@mui/material';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import './App.css';
import { Contract } from './Contract';
import DirectoryEntries from './DirectoryEntries';
import GroupSetup from './GroupSetup';
import Groups from './Groups';
import { LedgerApiClientProvider, useLedgerApiClient } from './LedgerApiContext';
import Login from './Login';
import { SplitwiseClientProvider, useSplitwiseClient } from './SplitwiseServiceContext';
import { sameContracts, useInterval } from './Util';
import { DirectoryProviderServiceClient } from './com/daml/network/directory_provider/v0/Directory_provider_serviceServiceClientPb';
import { ScanServiceClient } from './com/daml/network/scan/v0/Scan_serviceServiceClientPb';

const App: React.FC = () => {
  const [directoryEntries, setDirectoryEntries] = useState<Contract<DirectoryEntry>[]>([]);
  const dirEntries = new DirectoryEntries(directoryEntries);
  const directoryClient = useMemo(
    () => new DirectoryProviderServiceClient('http://localhost:8084'),
    []
  );
  const scanClient = useMemo(() => new ScanServiceClient('http://localhost:8083'), []);

  const fetchDirectoryEntries = useCallback(async () => {
    const newEntries = (await directoryClient.listEntries(new Empty(), null)).getEntriesList();
    const decoded = newEntries.map(c => Contract.decode(c, DirectoryEntry));
    setDirectoryEntries(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [setDirectoryEntries, directoryClient]);

  useInterval(fetchDirectoryEntries, 500);

  const [svc, setSvc] = useState<string | undefined>();
  useEffect(() => {
    const fetchSvc = async () => {
      const svc = await scanClient.getSvcPartyId(new Empty(), null);
      setSvc(svc.getSvcPartyId());
    };
    fetchSvc();
  }, [scanClient]);

  const [userId, setUserId] = useState<string | undefined>();

  const LoggedInBody: React.FC<{ userId: string }> = ({ userId }) => {
    const splitwiseClient = useSplitwiseClient();
    const ledgerApiClient = useLedgerApiClient();

    const [provider, setProvider] = useState<string | undefined>();
    const [party, setParty] = useState<string | undefined>();

    useEffect(() => {
      const fetchProvider = async () => {
        const provider = await splitwiseClient.getProviderPartyId(new Empty(), null);
        setProvider(provider.getPartyId());
      };
      fetchProvider();
    }, [splitwiseClient]);

    useEffect(() => {
      const fetchParty = async () => {
        const party = await ledgerApiClient.getPrimaryParty(userId);
        setParty(party);
      };
      fetchParty();
    }, [userId, ledgerApiClient]);

    if (provider && party && svc) {
      return (
        <Container>
          <Stack spacing={3}>
            <GroupSetup directoryEntries={dirEntries} party={party} provider={provider} svc={svc} />
            <Groups directoryEntries={dirEntries} party={party} provider={provider} />
          </Stack>
        </Container>
      );
    } else {
      return <Typography>Loading …</Typography>;
    }
  };

  const LoggedIn: React.FC<{ userId: string }> = ({ userId }) => (
    <LedgerApiClientProvider
      url={process.env.REACT_APP_LEDGER_API_GRPC_URL || 'http://localhost:8085'}
      userId={userId}
    >
      <LoggedInBody userId={userId} />
    </LedgerApiClientProvider>
  );

  return (
    <SplitwiseClientProvider url={process.env.REACT_APP_GRPC_URL || 'http://localhost:8082'}>
      <Box>
        <CssBaseline />
        <AppBar position="static" sx={{ marginBottom: 5 }}>
          <Toolbar>
            <Typography variant="h6">CN Splitwise</Typography>
          </Toolbar>
        </AppBar>
        {userId ? <LoggedIn userId={userId} /> : <Login onLogin={setUserId} />}
      </Box>
    </SplitwiseClientProvider>
  );
};

export default App;
