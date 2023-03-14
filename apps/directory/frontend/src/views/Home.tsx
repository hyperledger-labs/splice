import {
  Contract,
  DirectoryClientProvider,
  useDirectoryClient,
  useUserState,
} from 'common-frontend';
import { DirectoryClient } from 'common-frontend/lib/contexts/DirectoryServiceContext';
import { useEffect, useState } from 'react';

import { DirectoryInstall, DirectoryInstallRequest } from '@daml.js/directory/lib/CN/Directory';

import {
  LedgerApiClient,
  LedgerApiClientProvider,
  useLedgerApiClient,
} from '../contexts/LedgerApiContext';
import { config } from '../utils';
import DirectoryEntries from './DirectoryEntries';
import RequestDirectoryEntry from './RequestDirectoryEntry';

export function useProviderParty(directoryClient: DirectoryClient): string | undefined {
  const [providerPartyId, setProviderPartyId] = useState<string | undefined>();

  useEffect(() => {
    const fetchProviderParty = async () => {
      try {
        const response = await directoryClient.getProviderPartyId();
        setProviderPartyId(response.providerPartyId);
      } catch (err) {
        console.error('Error finding provider party', err);
        throw new Error('Error finding provider party, please confirm user onboarded.');
      }
    };
    fetchProviderParty();
  }, [directoryClient]);

  return providerPartyId;
}

export function usePrimaryParty(ledgerApiClient: LedgerApiClient): string | undefined {
  const [primaryParty, setPrimaryParty] = useState<string>();

  useEffect(() => {
    const fetchPrimaryParty = async () => {
      try {
        setPrimaryParty(await ledgerApiClient.getPrimaryParty());
      } catch (err) {
        console.error('Error finding primary party for user', err);
        console.error(JSON.stringify(err));
        throw new Error(
          'Error finding primary party for user, please confirm user onboarded to this participant.'
        );
      }
    };
    fetchPrimaryParty();
  }, [ledgerApiClient]);

  return primaryParty;
}

const Home: React.FC = () => {
  const { updateStatus } = useUserState();
  const [install, setInstall] = useState<Contract<DirectoryInstall> | undefined>();
  const ledgerApiClient = useLedgerApiClient();
  const directoryClient = useDirectoryClient();

  const primaryPartyId = usePrimaryParty(ledgerApiClient);
  const providerPartyId = useProviderParty(directoryClient);

  useEffect(() => {
    if (primaryPartyId) {
      updateStatus({ userOnboarded: true, partyId: primaryPartyId });
    }
  }, [primaryPartyId, updateStatus]);

  // We don’t expect to have console-based auth in Q4 so we
  // generate the install contract from the frontend rather than the backend.
  useEffect(() => {
    const setupInstallContract = async () => {
      if (primaryPartyId && providerPartyId) {
        console.debug('Searching for DirectoryInstall');
        const install = await ledgerApiClient.queryDirectoryInstall(
          primaryPartyId,
          providerPartyId
        );
        if (install) {
          console.debug('DirectoryInstall found');
          setInstall(install);
        } else {
          console.debug('DirectoryInstall not found, creating DirectoryInstallRequest');
          await ledgerApiClient.create([primaryPartyId], DirectoryInstallRequest, {
            user: primaryPartyId,
            provider: providerPartyId,
          });
          console.debug('Created DirectoryInstallRequest, waiting for DirectoryInstall');
          setTimeout(() => {
            const queryDirectoryInstall = async () => {
              const install = await ledgerApiClient.queryDirectoryInstall(
                primaryPartyId,
                providerPartyId
              );
              if (install) {
                console.debug('DirectoryInstall found');
                setInstall(install);
              } else {
                console.debug('DirectoryInstall not found, waiting before retrying');
                setTimeout(() => queryDirectoryInstall(), 500);
              }
            };
            queryDirectoryInstall();
          }, 500);
        }
      }
    };
    setupInstallContract();
  }, [primaryPartyId, providerPartyId, ledgerApiClient]);

  if (primaryPartyId && providerPartyId && install) {
    return (
      <div>
        <RequestDirectoryEntry primaryParty={primaryPartyId} provider={providerPartyId} />
        <DirectoryEntries primaryParty={primaryPartyId} provider={providerPartyId} />
      </div>
    );
  } else {
    return <span>Loading ...</span>;
  }
};

const HomeWithContexts: React.FC = () => {
  const { userAccessToken, userId } = useUserState();
  return (
    <LedgerApiClientProvider
      jsonApiUrl={config.services.directory.jsonApiUrl}
      userId={userId!}
      token={userAccessToken!}
    >
      <DirectoryClientProvider url={config.services.directory.url}>
        <Home />
      </DirectoryClientProvider>
    </LedgerApiClientProvider>
  );
};

export default HomeWithContexts;
