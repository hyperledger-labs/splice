// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ListVoteRequests } from 'common-frontend';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import { ScanAppVotesHooksProvider } from '../../contexts/ScanAppVotesHooksProvider';
import Layout from '../Layout';

const ScanListVoteRequests: React.FC = () => {
  return (
    <Layout>
      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg">
          <ScanAppVotesHooksProvider>
            <ListVoteRequests showActionNeeded={false} />
          </ScanAppVotesHooksProvider>
        </Container>
      </Box>
    </Layout>
  );
};

export default ScanListVoteRequests;
