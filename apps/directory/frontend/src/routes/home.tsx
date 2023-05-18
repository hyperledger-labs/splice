import { useUserState } from 'common-frontend';
import React, { useEffect } from 'react';

import { Box, Container } from '@mui/material';

import DirectoryEntries from '../components/DirectoryEntries';
import RequestDirectoryEntry from '../components/RequestDirectoryEntry';
import { useDirectoryInstall, usePrimaryParty } from '../hooks';

const Home: React.FC = () => {
  const { updateStatus } = useUserState();
  const { data: primaryPartyId } = usePrimaryParty(); // TODO(#4139) -- handle error state, especially if user not onboarded
  const directoryInstallQuery = useDirectoryInstall();

  useEffect(() => {
    if (primaryPartyId) {
      updateStatus({ userOnboarded: true, userWalletInstalled: true, partyId: primaryPartyId });
    }
  }, [primaryPartyId, updateStatus]);

  switch (directoryInstallQuery.status) {
    case 'error':
      // TODO(#4139) - add error page
      console.error(
        'Error retrieving directory install contract in Home.tsx: ',
        directoryInstallQuery.error
      );
      return null;
    case 'loading':
      // TODO(#4139) - improve loading page
      return <span>Loading ...</span>;
    case 'success':
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
  }
};

export default Home;
