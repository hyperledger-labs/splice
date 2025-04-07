// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ErrorDisplay, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  useAmuletPrice,
  useTotalRewards,
} from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import BigNumber from 'bignumber.js';

import AmountSummary from './AmountSummary';

export const TotalRewards: React.FC = () => {
  const totalRewardsQuery = useTotalRewards();
  const amuletPriceQuery = useAmuletPrice();

  const isLoading = totalRewardsQuery.isLoading || amuletPriceQuery.isLoading;
  const isError = totalRewardsQuery.isError || amuletPriceQuery.isError;

  return isLoading ? (
    <Loading />
  ) : isError ? (
    <ErrorDisplay message={'Could not retrieve total rewards or amulet price'} />
  ) : (
    <AmountSummary
      title="Total App & Validator Rewards"
      amount={new BigNumber(totalRewardsQuery.data.amount)}
      idCC="total-rewards-amulet"
      idUSD="total-rewards-usd"
      amuletPrice={amuletPriceQuery.data}
    />
  );
};

export default TotalRewards;
