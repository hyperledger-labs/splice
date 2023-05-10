import { useUserState } from 'common-frontend';
import React, { useEffect } from 'react';

import { Box, Container } from '@mui/material';

import DirectoryEntries from '../components/DirectoryEntries';
import RequestDirectoryEntry from '../components/RequestDirectoryEntry';
import { useDirectoryUiState } from '../contexts/DirectoryContext';

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

export default Home;
