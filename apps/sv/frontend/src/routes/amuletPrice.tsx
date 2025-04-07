// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box } from '@mui/material';

import DesiredAmuletPrice from '../components/amuletprice/DesiredAmuletPrice';
import MedianAmuletPrice from '../components/amuletprice/MedianAmuletPrice';
import OpenMiningRounds from '../components/amuletprice/OpenMiningRounds';

const AmuletPrice: React.FC = () => {
  return (
    <Box>
      <MedianAmuletPrice />
      <DesiredAmuletPrice canEditVote />
      <OpenMiningRounds />
    </Box>
  );
};

export default AmuletPrice;
