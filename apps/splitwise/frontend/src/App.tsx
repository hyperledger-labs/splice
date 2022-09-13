import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { AppBar, Box, Container, CssBaseline, Stack, Toolbar, Typography } from '@mui/material';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import './App.css';
import { Contract } from './Contract';
import DirectoryEntries from './DirectoryEntries';
import GroupSetup from './GroupSetup';
import Groups from './Groups';
import { SplitwiseClientProvider, useSplitwiseClient } from './SplitwiseServiceContext';
import { sameContracts, useInterval } from './Util';
import { DirectoryProviderServiceClient } from './com/daml/network/directory_provider/v0/Directory_provider_serviceServiceClientPb';

const App: React.FC = () => {
  const [directoryEntries, setDirectoryEntries] = useState<Contract<DirectoryEntry>[]>([]);
  const dirEntries = new DirectoryEntries(directoryEntries);
  const directoryClient = useMemo(
    () => new DirectoryProviderServiceClient('http://localhost:8084'),
    []
  );

  const fetchDirectoryEntries = useCallback(async () => {
    const newEntries = (await directoryClient.listEntries(new Empty(), null)).getEntriesList();
    const decoded = newEntries.map(c => Contract.decode(c, DirectoryEntry));
    setDirectoryEntries(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [setDirectoryEntries, directoryClient]);

  useInterval(fetchDirectoryEntries, 500);

  const Wrapper: React.FC = () => {
    const splitwiseClient = useSplitwiseClient();

    const [party, setParty] = useState<string>('');
    // For now, we only support self-hosted mode so provider = user party.
    const provider = party;

    useEffect(() => {
      const fetchParty = async () => {
        const party = await splitwiseClient.getProviderPartyId(new Empty(), null);
        setParty(party.getPartyId());
      };
      fetchParty();
    }, [setParty, splitwiseClient]);
    return (
      <Box>
        <CssBaseline />
        <AppBar position="static" sx={{ marginBottom: 5 }}>
          <Toolbar>
            <Typography variant="h6">CN Splitwise</Typography>
          </Toolbar>
        </AppBar>
        <Container>
          <Stack spacing={3}>
            <GroupSetup directoryEntries={dirEntries} provider={provider} />
            <Groups directoryEntries={dirEntries} party={party} provider={provider} />
          </Stack>
        </Container>
      </Box>
    );
  };

  return (
    <SplitwiseClientProvider url={process.env.REACT_APP_GRPC_URL || 'http://localhost:8082'}>
      <Wrapper />
    </SplitwiseClientProvider>
  );
};

export default App;
