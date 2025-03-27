// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ErrorDisplay, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  useAmuletPrice,
  useTotalAmuletBalance,
} from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import BigNumber from 'bignumber.js';

import { useScanConfig } from '../utils/config';
import AmountSummary from './AmountSummary';

export const TotalAmuletBalance: React.FC = () => {
  const config = useScanConfig();
  const totalAmuletBalanceQuery = useTotalAmuletBalance();
  const amuletPriceQuery = useAmuletPrice();

  const isLoading = totalAmuletBalanceQuery.isLoading || amuletPriceQuery.isLoading;
  const isError = totalAmuletBalanceQuery.isError || amuletPriceQuery.isError;
  const title = `Total Circulating ${config.spliceInstanceNames.amuletName}`;

  return isLoading ? (
    <Loading />
  ) : isError ? (
    <ErrorDisplay message={'Could not retrieve total amulet balance or amulet price'} />
  ) : (
    <AmountSummary
      title={title}
      amount={new BigNumber(totalAmuletBalanceQuery.data.total_balance)}
      idCC="total-amulet-balance-amulet"
      idUSD="total-amulet-balance-usd"
      amuletPrice={amuletPriceQuery.data}
      data-testid="amount-summary"
    />
  );
};

export default TotalAmuletBalance;
