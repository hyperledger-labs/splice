import { DirectoryClientProvider, useUserState } from 'common-frontend';
import React, { useEffect } from 'react';

import { Box, Container } from '@mui/material';

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
      updateStatus({ userOnboarded: true, userWalletInstalled: true, partyId: primaryPartyId });
    }
  }, [primaryPartyId, updateStatus]);

  if (directoryInstallContract) {
    return (
      <>
        <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" pb={4}>
          <Container maxWidth="md">
            <RequestDirectoryEntry />
          </Container>
        </Box>
        <Container maxWidth="md" sx={{ marginTop: '16px' }}>
          <DirectoryEntries />
        </Container>
      </>
    );
  } else {
    return <span>Loading ...</span>;
  }
};

const HomeWithContexts: React.FC = () => {
  const { userAccessToken, userId } = useUserState();
  return (
    <LedgerApiClientProvider
      jsonApiUrl={config.services.jsonApi.url}
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
