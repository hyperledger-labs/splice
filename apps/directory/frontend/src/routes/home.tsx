import { ErrorDisplay, Loading, usePrimaryParty } from 'common-frontend';
import React from 'react';

import { Box, Container } from '@mui/material';

import DirectoryEntries from '../components/DirectoryEntries';
import RequestDirectoryEntry from '../components/RequestDirectoryEntry';
import { useDirectoryInstall } from '../hooks';

const Home: React.FC = () => {
  const primaryPartyIdQuery = usePrimaryParty();
  const directoryInstallQuery = useDirectoryInstall();

  if (primaryPartyIdQuery.isError) {
    return <ErrorDisplay message={'Error while fetching primary party'} />;
  } else if (primaryPartyIdQuery.isLoading) {
    return <Loading />;
  }

  return (
    <>
      {directoryInstallQuery.isLoading ? (
        <Loading />
      ) : directoryInstallQuery.isError ? (
        <ErrorDisplay message={'Error while retrieving CNS entries'} />
      ) : (
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
      )}
    </>
  );
};

export default Home;
