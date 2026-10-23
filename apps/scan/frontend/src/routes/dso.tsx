// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DsoViewPrettyJSON } from '@canton-network/splice-common-frontend';
import React from 'react';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import Layout from '../components/Layout';
import { useDsoInfos } from '../hooks/useDsoInfos';
import { useSvInfo } from '../hooks/useSvInfo';

const DsoWithContexts: React.FC = () => {
  const dsoInfoQuery = useDsoInfos();
  const svInfoQuery = useSvInfo();
  return (
    <Layout>
      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg">
          <DsoViewPrettyJSON dsoInfoQuery={dsoInfoQuery} svInfoQuery={svInfoQuery} />
        </Container>
      </Box>
    </Layout>
  );
};

export default DsoWithContexts;
