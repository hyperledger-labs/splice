// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';

import { Box, Container } from '@mui/material';

import AnsEntries from '../components/AnsEntries';
import RequestAnsEntry from '../components/RequestAnsEntry';
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
          <RequestAnsEntry />
        </Container>
      </Box>
      <Container maxWidth="md" sx={{ marginTop: '16px' }}>
        <AnsEntries />
      </Container>
    </>
  );
};

export default Home;
