// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Box, Container } from '@mui/material';

import Layout from '../components/Layout';
import ListAmuletPriceVotes from '../components/votes/ListAmuletPriceVotes';

const AmuletPriceVotes: React.FC = () => {
  return (
    <Layout>
      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg" sx={{ marginTop: 2 }}>
          <ListAmuletPriceVotes />
        </Container>
      </Box>
    </Layout>
  );
};

export default AmuletPriceVotes;
