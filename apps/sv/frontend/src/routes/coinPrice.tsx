import * as React from 'react';

import { Box } from '@mui/material';

import DesiredCoinPrice from '../components/coinprice/DesiredCoinPrice';
import MedianCoinPrice from '../components/coinprice/MedianCoinPrice';

const CoinPrice: React.FC = () => {
  return (
    <Box>
      <MedianCoinPrice />
      <DesiredCoinPrice />
    </Box>
  );
};

export default CoinPrice;
