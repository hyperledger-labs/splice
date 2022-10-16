import { Contract } from 'common-frontend';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useState, useEffect } from 'react';

import { Container, Stack, Typography } from '@mui/material';

import { SplitwiseInstall, SplitwiseInstallRequest } from '@daml.js/splitwise/lib/CN/Splitwise';

import DirectoryEntries from './DirectoryEntries';
import GroupSetup from './GroupSetup';
import Groups from './Groups';
import { useLedgerApiClient, LedgerApiClientProvider } from './LedgerApiContext';
import { useSplitwiseClient } from './SplitwiseServiceContext';

const HomeWithContext: React.FC<{
  userId: string;
  svc: string | undefined;
  dirEntries: DirectoryEntries;
}> = ({ userId, svc, dirEntries }) => {
  const splitwiseClient = useSplitwiseClient();
  const ledgerApiClient = useLedgerApiClient();

  const [provider, setProvider] = useState<string | undefined>();
  const [party, setParty] = useState<string | undefined>();
  const [install, setInstall] = useState<Contract<SplitwiseInstall> | undefined>();

  useEffect(() => {
    const fetchProvider = async () => {
      const provider = await splitwiseClient.getProviderPartyId(new Empty(), undefined);
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

  // We don’t expect to have console-based auth in Q4 so we
  // generate the install contract from the frontend rather than the backend.
  useEffect(() => {
    const setupInstallContract = async () => {
      if (party && provider) {
        console.debug('Searching for SplitwiseInstall');
        const install = await ledgerApiClient.querySplitwiseInstall(party, provider);
        if (install) {
          console.debug('SplitwiseInstall found');
          setInstall(install);
        } else {
          console.debug('SplitwiseInstall not found, creating SplitwiseInstallRequest');
          await ledgerApiClient.create([party], SplitwiseInstallRequest, {
            user: party,
            provider: provider,
          });
          console.debug('Created SplitwiseInstallRequest, waiting for SplitwiseInstall');
          setTimeout(() => {
            const maxRetries = 30;
            const querySplitwiseInstall = async (n: number) => {
              const install = await ledgerApiClient.querySplitwiseInstall(party, provider);
              if (install) {
                console.debug('SplitwiseInstall found');
                setInstall(install);
              } else if (n > 0) {
                console.debug('SplitwiseInstall not found, waiting before retrying');
                setTimeout(() => querySplitwiseInstall(n - 1), 500);
              } else {
                throw new Error(
                  `SplitwiseInstall not found after ${maxRetries} retries, giving up`
                );
              }
            };
            querySplitwiseInstall(maxRetries);
          }, 500);
        }
      }
    };
    setupInstallContract();
  }, [party, provider, ledgerApiClient]);

  if (provider && party && svc && install) {
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

const Home: React.FC<{ userId: string; svc: string | undefined; dirEntries: DirectoryEntries }> = ({
  userId,
  svc,
  dirEntries,
}) => (
  <LedgerApiClientProvider
    url={process.env.REACT_APP_LEDGER_API_GRPC_URL || 'http://localhost:8085'}
    userId={userId}
  >
    <HomeWithContext userId={userId} svc={svc} dirEntries={dirEntries} />
  </LedgerApiClientProvider>
);

export default Home;
