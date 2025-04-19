// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { ListVoteRequests, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { useDsoInfo } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import { useFeatureSupport } from '../../hooks/useFeatureSupport';
import Layout from '../Layout';

//TODO(#16139): retire this logics
const ScanListVoteRequests: React.FC = () => {
  const dsoInfosQuery = useDsoInfo();
  const featureSupport = useFeatureSupport();
  if (dsoInfosQuery.isLoading || featureSupport.isLoading) {
    return <Loading />;
  }
  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }
  if (!dsoInfosQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }
  const supportNewGovernanceFlow = featureSupport.data?.newGovernanceFlow || false;
  return (
    <Layout>
      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg">
          <ListVoteRequests
            supportsVoteEffectivityAndSetConfig={supportNewGovernanceFlow}
            showActionNeeded={false}
          />
        </Container>
      </Box>
    </Layout>
  );
};

export default ScanListVoteRequests;
