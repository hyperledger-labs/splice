import * as React from 'react';

import { Box } from '@mui/material';

import DesiredAmuletPrice from '../components/amuletprice/DesiredAmuletPrice';
import MedianAmuletPrice from '../components/amuletprice/MedianAmuletPrice';
import OpenMiningRounds from '../components/amuletprice/OpenMiningRounds';

const AmuletPrice: React.FC = () => {
  return (
    <Box>
      <MedianAmuletPrice />
      <DesiredAmuletPrice />
      <OpenMiningRounds />
    </Box>
  );
};

export default AmuletPrice;
