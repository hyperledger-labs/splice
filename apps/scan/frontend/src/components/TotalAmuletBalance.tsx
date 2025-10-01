// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ErrorDisplay, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { useAmuletPrice } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import BigNumber from 'bignumber.js';

import { useScanConfig } from '../utils/config';
import AmountSummary from './AmountSummary';
import { useAmuletMetadata } from '../hooks/useAmuletMetadata';

export const TotalAmuletBalance: React.FC = () => {
  const config = useScanConfig();
  const amuletMetadataQuery = useAmuletMetadata();
  const amuletPriceQuery = useAmuletPrice();

  const isLoading = amuletMetadataQuery.isLoading || amuletPriceQuery.isLoading;
  const isError = amuletMetadataQuery.isError || amuletPriceQuery.isError;
  const isDataUndefined =
    amuletMetadataQuery.data === undefined ||
    !amuletMetadataQuery.data.totalSupply ||
    amuletPriceQuery.data === undefined;
  const title = `Total Circulating ${config.spliceInstanceNames.amuletName}`;

  if (isLoading) {
    return <Loading />;
  } else if (isError || isDataUndefined) {
    return <ErrorDisplay message={'Could not retrieve total amulet balance or amulet price'} />;
  } else {
    const totalSupply = new BigNumber(amuletMetadataQuery.data.totalSupply || '0');
    const asOf = amuletMetadataQuery.data?.totalSupplyAsOf;
    return (
      <AmountSummary
        title={title}
        amount={totalSupply}
        asOf={asOf}
        idCC="total-amulet-balance-amulet"
        idUSD="total-amulet-balance-usd"
        amuletPrice={amuletPriceQuery.data}
        data-testid="amount-summary"
      />
    );
  }
};

export default TotalAmuletBalance;
