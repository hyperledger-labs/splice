import { Loading } from 'common-frontend';
import React from 'react';

import { Box, Container } from '@mui/material';

import CnsEntries from '../components/CnsEntries';
import RequestCnsEntry from '../components/RequestCnsEntry';
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
          <RequestCnsEntry />
        </Container>
      </Box>
      <Container maxWidth="md" sx={{ marginTop: '16px' }}>
        <CnsEntries />
      </Container>
    </>
  );
};

export default Home;
