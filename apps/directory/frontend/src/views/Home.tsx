import { DirectoryClientProvider, useUserState } from 'common-frontend';
import { useEffect } from 'react';

import { DirectoryUiStateProvider, useDirectoryUiState } from '../contexts/DirectoryContext';
import { LedgerApiClientProvider } from '../contexts/LedgerApiContext';
import { config } from '../utils';
import DirectoryEntries from './DirectoryEntries';
import RequestDirectoryEntry from './RequestDirectoryEntry';

const Home: React.FC = () => {
  const { updateStatus } = useUserState();
  const { primaryPartyId, directoryInstallContract } = useDirectoryUiState();

  useEffect(() => {
    if (primaryPartyId) {
      updateStatus({ userOnboarded: true, partyId: primaryPartyId });
    }
  }, [primaryPartyId, updateStatus]);

  if (directoryInstallContract) {
    return (
      <div>
        <RequestDirectoryEntry />
        <DirectoryEntries />
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
        <DirectoryUiStateProvider>
          <Home />
        </DirectoryUiStateProvider>
      </DirectoryClientProvider>
    </LedgerApiClientProvider>
  );
};

export default HomeWithContexts;
