// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { ListVoteRequests, Loading } from '@canton-network/splice-common-frontend';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import { useDsoInfos } from '../../hooks/useDsoInfos';
import Layout from '../Layout';

const ScanListVoteRequests: React.FC = () => {
  const dsoInfosQuery = useDsoInfos();
  if (dsoInfosQuery.isLoading) {
    return <Loading />;
  }
  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }
  if (!dsoInfosQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }
  return (
    <Layout>
      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg">
          <ListVoteRequests showActionNeeded={false} />
        </Container>
      </Box>
    </Layout>
  );
};

export default ScanListVoteRequests;
