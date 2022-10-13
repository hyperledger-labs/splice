import { Contract } from 'common-frontend';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useEffect, useState } from 'react';

import { DirectoryInstall, DirectoryInstallRequest } from '@daml.js/directory/lib/CN/Directory';

import { DirectoryClientProvider, useDirectoryClient } from '../contexts/DirectoryServiceContext';
import { LedgerApiClientProvider, useLedgerApiClient } from '../contexts/LedgerApiContext';
import { config } from '../utils';
import DirectoryEntries from './DirectoryEntries';
import RequestDirectoryEntry from './RequestDirectoryEntry';

const Home: React.FC<{ userId: string }> = ({ userId }) => {
  const [primaryParty, setPrimaryParty] = useState<string | undefined>();
  const [providerParty, setProviderParty] = useState<string | undefined>();
  const [install, setInstall] = useState<Contract<DirectoryInstall> | undefined>();
  const ledgerApiClient = useLedgerApiClient();
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

const HomeWithContexts: React.FC<{ userId: string }> = ({ userId }) => {
  return (
    <LedgerApiClientProvider url={config.ledgerApi.grpcUrl} userId={userId}>
      <DirectoryClientProvider url={config.directory.grpcUrl}>
        <Home userId={userId} />
      </DirectoryClientProvider>
    </LedgerApiClientProvider>
  );
};

export default HomeWithContexts;
