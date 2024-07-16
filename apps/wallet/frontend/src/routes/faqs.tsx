// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import { Box, Link, Typography } from '@mui/material';

import { config } from '../utils/config';

export const Faqs: React.FC = () => {
  return (
    <Box display="flex" height="100%" flexDirection="column" justifyContent="center" marginTop={4}>
      <Box
        bgcolor="colors.neutral.15"
        flex={1}
        display="flex"
        flexDirection="column"
        justifyContent="space-between"
        height="100%"
      >
        <Typography variant="h4">FAQs</Typography>
        <Box marginTop={2}>
          <Typography variant="body1">
            More complete FAQs are coming soon. In the meantime, please visit the official{' '}
            <Link href={config.clusterUrl} target="_blank">
              {config.spliceInstanceNames.networkName} documentation
            </Link>{' '}
            for more information.
          </Typography>
        </Box>
      </Box>
    </Box>
  );
};

export default Faqs;
