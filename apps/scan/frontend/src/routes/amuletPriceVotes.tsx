// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Box, Container } from '@mui/material';

import Layout from '../components/Layout';
import ListAmuletPriceVotes from '../components/votes/ListAmuletPriceVotes';
import { MedianAmuletPrice } from '@lfdecentralizedtrust/splice-common-frontend';
import { useScanConfig } from '../utils';

const AmuletPriceVotes: React.FC = () => {
  const config = useScanConfig();

  return (
    <Layout>
      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg" sx={{ marginTop: 2 }}>
          <MedianAmuletPrice amuletName={config.spliceInstanceNames.amuletName} />
          <ListAmuletPriceVotes />
        </Container>
      </Box>
    </Layout>
  );
};

export default AmuletPriceVotes;
