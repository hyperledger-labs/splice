// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DsoViewPrettyJSON } from '@lfdecentralizedtrust/splice-common-frontend';
import { useDsoInfo } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import React from 'react';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import Layout from '../components/Layout';

const DsoWithContexts: React.FC = () => {
  const dsoInfoQuery = useDsoInfo();
  return (
    <Layout>
      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg">
          <DsoViewPrettyJSON dsoInfoQuery={dsoInfoQuery} />
        </Container>
      </Box>
    </Layout>
  );
};

export default DsoWithContexts;
