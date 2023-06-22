import BigNumber from 'bignumber.js';
import { AmountDisplay, Loading } from 'common-frontend';
import React, { useMemo } from 'react';

import { Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { useCoinPriceVotes } from '../../hooks/useCoinPriceVotes';

const MedianCoinPrice: React.FC = () => {
  const coinPriceVotesQuery = useCoinPriceVotes();

  const median = (votedPrices: BigNumber[]) => {
    if (votedPrices && votedPrices.length > 0) {
      const sorted = [...votedPrices].sort((a, b) => {
        return a.isEqualTo(b) ? 0 : a.isLessThan(b) ? -1 : 1;
      });
      const length = sorted.length;
      const half = Math.floor(length / 2);
      return length % 2 !== 0
        ? sorted[half]
        : sorted[half - 1].plus(sorted[half]).multipliedBy(0.5);
    }
    return undefined;
  };

  const coinPrices = useMemo(
    () =>
      coinPriceVotesQuery.data
        ?.map(v => (v.coinPrice ? new BigNumber(v.coinPrice) : undefined))
        .filter((p): p is BigNumber => !!p),
    [coinPriceVotesQuery.data]
  );

  const medianCoinPrice = useMemo(
    () => (coinPrices ? median(coinPrices) : undefined),
    [coinPrices]
  );

  if (coinPriceVotesQuery.isLoading) {
    return <Loading />;
  }

  if (coinPriceVotesQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  return (
    <Stack mt={4} spacing={2} direction="column" justifyContent="center">
      <Typography mt={4} variant="h4">
        Coin Price for Next Open Mining Round
      </Typography>
      <Typography id="median-coin-price-usd" variant="h2">
        {medianCoinPrice && <AmountDisplay amount={medianCoinPrice} currency="USD" />}
      </Typography>
      <Typography variant="caption">Median of coin prices voted by all Super Validators</Typography>
    </Stack>
  );
};

export default MedianCoinPrice;
