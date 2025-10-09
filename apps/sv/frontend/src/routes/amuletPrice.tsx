// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box } from '@mui/material';

import { MedianAmuletPrice } from '@lfdecentralizedtrust/splice-common-frontend';
import DesiredAmuletPrice from '../components/amuletprice/DesiredAmuletPrice';
import OpenMiningRounds from '../components/amuletprice/OpenMiningRounds';
import { useSvConfig } from '../utils';

const AmuletPrice: React.FC = () => {
  const config = useSvConfig();

  return (
    <Box>
      <MedianAmuletPrice amuletName={config.spliceInstanceNames.amuletName} />
      <DesiredAmuletPrice canEditVote />
      <OpenMiningRounds />
    </Box>
  );
};

export default AmuletPrice;
