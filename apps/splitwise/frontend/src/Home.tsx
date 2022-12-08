import { Contract, useUserState } from 'common-frontend';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useState, useEffect } from 'react';

import { Container, Stack, Typography } from '@mui/material';

import { SplitwiseInstall, SplitwiseInstallRequest } from '@daml.js/splitwise/lib/CN/Splitwise';

import DirectoryEntries from './DirectoryEntries';
import GroupSetup from './GroupSetup';
import Groups from './Groups';
import {
  useSplitwiseLedgerApiClient,
  SplitwiseLedgerApiClientProvider,
} from './contexts/SplitwiseLedgerApiContext';
import { useSplitwiseClient } from './contexts/SplitwiseServiceContext';
import { config } from './utils/config';

const HomeWithContext: React.FC<{
  userId: string;
  svc: string | undefined;
  dirEntries: DirectoryEntries;
}> = ({ userId, svc, dirEntries }) => {
  const splitwiseClient = useSplitwiseClient();
  const ledgerApiClient = useSplitwiseLedgerApiClient();
  const { updateStatus } = useUserState();

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
      const partyId = await ledgerApiClient.getPrimaryParty();
      setParty(partyId);
      updateStatus({ userOnboarded: true, partyId });
    };
    fetchParty();
  }, [userId, updateStatus, ledgerApiClient]);

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
          <GroupSetup party={party} provider={provider} svc={svc} />
          <Groups directoryEntries={dirEntries} party={party} provider={provider} />
        </Stack>
      </Container>
    );
  } else {
    return <Typography>Loading …</Typography>;
  }
};

interface HomeProps {
  userId: string;
  svc: string | undefined;
  dirEntries: DirectoryEntries;
  ledgerApiToken: string;
}

const Home: React.FC<HomeProps> = ({ userId, svc, dirEntries, ledgerApiToken }) => {
  return (
    <SplitwiseLedgerApiClientProvider
      url={config.services.ledgerApi.grpcUrl}
      userId={userId}
      token={ledgerApiToken}
    >
      <HomeWithContext userId={userId} svc={svc} dirEntries={dirEntries} />
    </SplitwiseLedgerApiClientProvider>
  );
};

export default Home;
