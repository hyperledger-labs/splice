import { Loading } from 'common-frontend';
import React from 'react';

import { Box, Container } from '@mui/material';

import DirectoryEntries from '../components/DirectoryEntries';
import RequestDirectoryEntry from '../components/RequestDirectoryEntry';
import { usePrimaryParty } from '../hooks/queries/usePrimaryParty';

const Home: React.FC = () => {
  const primaryPartyId = usePrimaryParty();

  if (!primaryPartyId) {
    return <Loading />;
  }

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
};

export default Home;
