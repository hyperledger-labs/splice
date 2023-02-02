import { Contract, usePrimaryParty, useUserState } from 'common-frontend';
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
  splitwiseDomainAlias: string;
}> = ({ userId, svc, dirEntries, splitwiseDomainAlias }) => {
  const splitwiseClient = useSplitwiseClient();
  const ledgerApiClient = useSplitwiseLedgerApiClient();
  const { updateStatus } = useUserState();

  const [provider, setProvider] = useState<string | undefined>();
  const [install, setInstall] = useState<Contract<SplitwiseInstall> | undefined>();
  const [splitwiseDomainId, setSplitwiseDomainId] = useState<string | undefined>();

  const primaryPartyId = usePrimaryParty(ledgerApiClient);

  useEffect(() => {
    if (primaryPartyId) {
      updateStatus({ userOnboarded: true, partyId: primaryPartyId });
    }
  }, [primaryPartyId, updateStatus]);

  useEffect(() => {
    const fetchProvider = async () => {
      const provider = await splitwiseClient.getProviderPartyId(new Empty(), undefined);
      setProvider(provider.getPartyId());
    };
    fetchProvider();
  }, [splitwiseClient]);

  useEffect(() => {
    const querySplitwiseDomain = async () => {
      // TODO(M3-18) Reconsider if we should query that from the user’s participant instead
      // instead of the app backend once the ledger API exposes this.
      console.debug('Querying backend for splitwise domain');
      const domainsResponse = await splitwiseClient.getSplitwiseDomainId(new Empty(), undefined);
      const domainId = domainsResponse.getDomainId();
      console.debug(`Using splitwise domain id ${domainId}`);
      setSplitwiseDomainId(domainId);
    };

    querySplitwiseDomain();
  }, [splitwiseClient, splitwiseDomainAlias]);

  // We don’t expect to have console-based auth in Q4 so we
  // generate the install contract from the frontend rather than the backend.
  useEffect(() => {
    const setupInstallContract = async () => {
      if (primaryPartyId && provider && splitwiseDomainId) {
        console.debug('Searching for SplitwiseInstall');
        const install = await ledgerApiClient.querySplitwiseInstall(primaryPartyId, provider);
        if (install) {
          console.debug('SplitwiseInstall found');
          setInstall(install);
        } else {
          console.debug('SplitwiseInstall not found, creating SplitwiseInstallRequest');
          await ledgerApiClient.create(
            [primaryPartyId],
            SplitwiseInstallRequest,
            {
              user: primaryPartyId,
              provider: provider,
            },
            splitwiseDomainId
          );
          console.debug('Created SplitwiseInstallRequest, waiting for SplitwiseInstall');
          setTimeout(() => {
            const maxRetries = 30;
            const querySplitwiseInstall = async (n: number) => {
              const install = await ledgerApiClient.querySplitwiseInstall(primaryPartyId, provider);
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
  }, [primaryPartyId, provider, ledgerApiClient, splitwiseDomainId]);

  if (provider && primaryPartyId && svc && install && splitwiseDomainId) {
    return (
      <Container>
        <Stack spacing={3}>
          <GroupSetup
            party={primaryPartyId}
            provider={provider}
            svc={svc}
            domainId={splitwiseDomainId}
          />
          <Groups
            directoryEntries={dirEntries}
            party={primaryPartyId}
            provider={provider}
            domainId={splitwiseDomainId}
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
    <SplitwiseLedgerApiClientProvider
      url={config.services.ledgerApi.grpcUrl}
      userId={userId}
      token={ledgerApiToken}
    >
      <HomeWithContext
        userId={userId}
        svc={svc}
        dirEntries={dirEntries}
        splitwiseDomainAlias={config.domains.splitwise}
      />
    </SplitwiseLedgerApiClientProvider>
  );
};

export default Home;
