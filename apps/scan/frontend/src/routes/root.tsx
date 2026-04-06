// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Typography, Stack, Box, Container } from '@mui/material';

import Layout from '../components/Layout';
import NetworkInfo from '../components/NetworkInfo';
import TotalAmuletBalance from '../components/TotalAmuletBalance';

const Root: React.FC = () => {
  return (
    <Layout>
      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg" sx={{ marginTop: 2 }}>
          <Stack mt={4} spacing={2} direction="column" justifyContent="center">
            <Typography variant="h5">
              Explore, search and find answers to current network configuration details.
            </Typography>

            <div data-testid="circulating-supply-container">
              <TotalAmuletBalance />
            </div>

            <NetworkInfo />
          </Stack>
        </Container>
      </Box>
    </Layout>
  );
};

export default Root;
