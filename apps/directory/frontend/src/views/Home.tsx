import { Contract } from 'common-frontend';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useEffect, useState } from 'react';

import { DirectoryInstall, DirectoryInstallRequest } from '@daml.js/directory/lib/CN/Directory';

import {
  DirectoryLedgerApiClientProvider,
  useDirectoryLedgerApiClient,
} from '../contexts/DirectoryLedgerApiContext';
import { DirectoryClientProvider, useDirectoryClient } from '../contexts/DirectoryServiceContext';
import { config } from '../utils';
import DirectoryEntries from './DirectoryEntries';
import RequestDirectoryEntry from './RequestDirectoryEntry';

const Home: React.FC<{ userId: string }> = ({ userId }) => {
  const [primaryParty, setPrimaryParty] = useState<string | undefined>();
  const [providerParty, setProviderParty] = useState<string | undefined>();
  const [install, setInstall] = useState<Contract<DirectoryInstall> | undefined>();
  const ledgerApiClient = useDirectoryLedgerApiClient();
  const directoryClient = useDirectoryClient();
  useEffect(() => {
    const fetchPrimaryParty = async () => {
      setPrimaryParty(await ledgerApiClient.getPrimaryParty());
    };
    fetchPrimaryParty();
  }, [ledgerApiClient]);

  useEffect(() => {
    const fetchProviderParty = async () => {
      const response = await directoryClient.getProviderPartyId(new Empty(), undefined);
      setProviderParty(response.getProviderPartyId());
    };
    fetchProviderParty();
  }, [directoryClient]);

  // We don’t expect to have console-based auth in Q4 so we
  // generate the install contract from the frontend rather than the backend.
  useEffect(() => {
    const setupInstallContract = async () => {
      if (primaryParty && providerParty) {
        console.debug('Searching for DirectoryInstall');
        const install = await ledgerApiClient.queryDirectoryInstall(primaryParty, providerParty);
        if (install) {
          console.debug('DirectoryInstall found');
          setInstall(install);
        } else {
          console.debug('DirectoryInstall not found, creating DirectoryInstallRequest');
          await ledgerApiClient.create([primaryParty], DirectoryInstallRequest, {
            user: primaryParty,
            provider: providerParty,
          });
          console.debug('Created DirectoryInstallRequest, waiting for DirectoryInstall');
          setTimeout(() => {
            const queryDirectoryInstall = async () => {
              const install = await ledgerApiClient.queryDirectoryInstall(
                primaryParty,
                providerParty
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
  }, [primaryParty, providerParty, ledgerApiClient]);

  if (primaryParty && providerParty && install) {
    return (
      <div>
        <RequestDirectoryEntry primaryParty={primaryParty} provider={providerParty} />
        <DirectoryEntries primaryParty={primaryParty} provider={providerParty} />
      </div>
    );
  } else {
    return <span>Loading ...</span>;
  }
};

const HomeWithContexts: React.FC<{ userId: string; ledgerApiToken: string }> = ({
  userId,
  ledgerApiToken,
}) => {
  return (
    <DirectoryLedgerApiClientProvider
      url={config.services.ledgerApi.grpcUrl}
      userId={userId}
      token={ledgerApiToken}
    >
      <DirectoryClientProvider url={config.services.directory.grpcUrl}>
        <Home userId={userId} />
      </DirectoryClientProvider>
    </DirectoryLedgerApiClientProvider>
  );
};

export default HomeWithContexts;
