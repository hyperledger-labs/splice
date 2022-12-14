import {
  Contract,
  DirectoryClientProvider,
  useDirectoryClient,
  useUserState,
} from 'common-frontend';
import { useEffect, useState } from 'react';

import { DirectoryInstall, DirectoryInstallRequest } from '@daml.js/directory/lib/CN/Directory';

import {
  DirectoryLedgerApiClientProvider,
  useDirectoryLedgerApiClient,
} from '../contexts/DirectoryLedgerApiContext';
import { config } from '../utils';
import DirectoryEntries from './DirectoryEntries';
import RequestDirectoryEntry from './RequestDirectoryEntry';

const Home: React.FC = () => {
  const { primaryPartyId, updateStatus } = useUserState();
  const [providerPartyId, setProviderPartyId] = useState<string | undefined>();
  const [install, setInstall] = useState<Contract<DirectoryInstall> | undefined>();
  const ledgerApiClient = useDirectoryLedgerApiClient();
  const directoryClient = useDirectoryClient();
  useEffect(() => {
    const fetchPrimaryParty = async () => {
      const partyId = await ledgerApiClient.getPrimaryParty();
      updateStatus({ userOnboarded: true, partyId });
    };
    fetchPrimaryParty();
  }, [ledgerApiClient, updateStatus]);

  useEffect(() => {
    const fetchProviderParty = async () => {
      const response = await directoryClient.getProviderPartyId();
      setProviderPartyId(response.providerPartyId);
    };
    fetchProviderParty();
  }, [directoryClient]);

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
    <DirectoryLedgerApiClientProvider
      url={config.services.ledgerApi.grpcUrl}
      userId={userId!}
      token={userAccessToken!}
    >
      <DirectoryClientProvider url={config.services.directory.grpcUrl}>
        <Home />
      </DirectoryClientProvider>
    </DirectoryLedgerApiClientProvider>
  );
};

export default HomeWithContexts;
