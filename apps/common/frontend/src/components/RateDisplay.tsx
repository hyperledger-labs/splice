// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import BigNumber from 'bignumber.js';

import { Unit } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';

type RateDisplayProps = {
  base: Unit;
  quote: Unit;
  amuletPrice: number | BigNumber;
};

const RateDisplay: React.FC<RateDisplayProps> = props => {
  const { base, quote, amuletPrice } = props;
  const amuletAcronym = window.splice_config.spliceInstanceNames?.amuletNameAcronym;
  let amount, rate;
  switch (true) {
    case base === 'AmuletUnit' && quote === 'USDUnit':
      amount = BigNumber(1).div(amuletPrice);
      rate = `${amuletAcronym}/USD`;
      break;
    case base === 'USDUnit' && quote === 'AmuletUnit':
      amount = amuletPrice;
      rate = `USD/${amuletAcronym}`;
      break;
    default:
      throw Error(`Rate not properly defined.: base: ${base}, quote: ${quote}`);
  }
  return (
    <>
      {amount.toString()} {rate}
    </>
  );
};

export default RateDisplay;
