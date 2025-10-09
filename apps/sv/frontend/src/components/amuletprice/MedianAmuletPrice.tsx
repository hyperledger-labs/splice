// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  AmountDisplay,
  Loading,
  medianPriceVotes,
  useVotesHooks,
} from '@lfdecentralizedtrust/splice-common-frontend';
import BigNumber from 'bignumber.js';
import React, { useMemo } from 'react';

import { Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

interface MedianAmuletPriceProps {
  amuletName: string;
}

export const MedianAmuletPrice: React.FC<MedianAmuletPriceProps> = props => {
  const { amuletName } = props;

  const voteHooks = useVotesHooks();
  const amuletPriceVotesQuery = voteHooks.useAmuletPriceVotes();

  const amuletPrices = useMemo(
    () =>
      amuletPriceVotesQuery.data
        ?.map(v => (v.amuletPrice ? new BigNumber(v.amuletPrice) : undefined))
        .filter((p): p is BigNumber => !!p),
    [amuletPriceVotesQuery.data]
  );

  const medianAmuletPrice = useMemo(
    () => (amuletPrices ? medianPriceVotes(amuletPrices) : undefined),
    [amuletPrices]
  );

  if (amuletPriceVotesQuery.isLoading) {
    return <Loading />;
  }

  if (amuletPriceVotesQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  return (
    <Stack mt={4} spacing={2} direction="column" justifyContent="center">
      <Typography mt={4} variant="h4">
        {amuletName} Price for Next Open Mining Round
      </Typography>
      <Typography id="median-amulet-price-usd" variant="h2">
        {medianAmuletPrice && <AmountDisplay amount={medianAmuletPrice} currency="USDUnit" />}
      </Typography>
      <Typography variant="caption">
        Median of {amuletName} prices voted by all Super Validators
      </Typography>
    </Stack>
  );
};
