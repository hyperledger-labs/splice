import * as React from 'react';

import { Box } from '@mui/material';

import DesiredCoinPrice from '../components/coinprice/DesiredCoinPrice';
import MedianCoinPrice from '../components/coinprice/MedianCoinPrice';
import OpenMiningRounds from '../components/coinprice/OpenMiningRounds';

const CoinPrice: React.FC = () => {
  return (
    <Box>
      <MedianCoinPrice />
      <DesiredCoinPrice />
      <OpenMiningRounds />
    </Box>
  );
};

export default CoinPrice;
