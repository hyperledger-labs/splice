import { Contract, usePrimaryParty, useUserState } from 'common-frontend';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useState, useEffect } from 'react';

import { Container, Stack, Typography } from '@mui/material';

import { SplitwellInstall, SplitwellInstallRequest } from '@daml.js/splitwell/lib/CN/Splitwell';

import DirectoryEntries from './DirectoryEntries';
import GroupSetup from './GroupSetup';
import Groups from './Groups';
import {
  useSplitwellLedgerApiClient,
  SplitwellLedgerApiClientProvider,
} from './contexts/SplitwellLedgerApiContext';
import { useSplitwellClient } from './contexts/SplitwellServiceContext';
import { config } from './utils/config';

const HomeWithContext: React.FC<{
  userId: string;
  svc: string | undefined;
  dirEntries: DirectoryEntries;
}> = ({ userId, svc, dirEntries }) => {
  const splitwellClient = useSplitwellClient();
  const ledgerApiClient = useSplitwellLedgerApiClient();
  const { updateStatus } = useUserState();

  const [provider, setProvider] = useState<string | undefined>();
  const [install, setInstall] = useState<Contract<SplitwellInstall> | undefined>();
  const [splitwellDomainId, setSplitwellDomainId] = useState<string | undefined>();

  const primaryPartyId = usePrimaryParty(ledgerApiClient);

  useEffect(() => {
    if (primaryPartyId) {
      updateStatus({ userOnboarded: true, partyId: primaryPartyId });
    }
  }, [primaryPartyId, updateStatus]);

  useEffect(() => {
    const fetchProvider = async () => {
      const provider = await splitwellClient.getProviderPartyId(new Empty(), undefined);
      setProvider(provider.getPartyId());
    };
    fetchProvider();
  }, [splitwellClient]);

  useEffect(() => {
    const querySplitwellDomain = async () => {
      console.debug('Querying backend for splitwell domain');
      const domainsResponse = await splitwellClient.getSplitwellDomainId(new Empty(), undefined);
      const domainId = domainsResponse.getDomainId();
      console.debug(`Using splitwell domain id ${domainId}`);
      setSplitwellDomainId(domainId);
    };

    querySplitwellDomain();
  }, [splitwellClient]);

  // We don’t expect to have console-based auth in Q4 so we
  // generate the install contract from the frontend rather than the backend.
  useEffect(() => {
    const setupInstallContract = async () => {
      if (primaryPartyId && provider && splitwellDomainId) {
        console.debug('Searching for SplitwellInstall');
        const install = await ledgerApiClient.querySplitwellInstall(primaryPartyId, provider);
        if (install) {
          console.debug('SplitwellInstall found');
          setInstall(install);
        } else {
          console.debug('SplitwellInstall not found, creating SplitwellInstallRequest');
          await ledgerApiClient.create(
            [primaryPartyId],
            SplitwellInstallRequest,
            {
              user: primaryPartyId,
              provider: provider,
            },
            splitwellDomainId
          );
          console.debug('Created SplitwellInstallRequest, waiting for SplitwellInstall');
          setTimeout(() => {
            const maxRetries = 30;
            const querySplitwellInstall = async (n: number) => {
              const install = await ledgerApiClient.querySplitwellInstall(primaryPartyId, provider);
              if (install) {
                console.debug('SplitwellInstall found');
                setInstall(install);
              } else if (n > 0) {
                console.debug('SplitwellInstall not found, waiting before retrying');
                setTimeout(() => querySplitwellInstall(n - 1), 500);
              } else {
                throw new Error(
                  `SplitwellInstall not found after ${maxRetries} retries, giving up`
                );
              }
            };
            querySplitwellInstall(maxRetries);
          }, 500);
        }
      }
    };
    setupInstallContract();
  }, [primaryPartyId, provider, ledgerApiClient, splitwellDomainId]);

  if (provider && primaryPartyId && svc && install && splitwellDomainId) {
    return (
      <Container>
        <Stack spacing={3}>
          <GroupSetup
            party={primaryPartyId}
            provider={provider}
            svc={svc}
            domainId={splitwellDomainId}
          />
          <Groups
            directoryEntries={dirEntries}
            party={primaryPartyId}
            provider={provider}
            domainId={splitwellDomainId}
          />
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
    <SplitwellLedgerApiClientProvider
      url={config.services.ledgerApi.grpcUrl}
      userId={userId}
      token={ledgerApiToken}
    >
      <HomeWithContext userId={userId} svc={svc} dirEntries={dirEntries} />
    </SplitwellLedgerApiClientProvider>
  );
};

export default Home;
