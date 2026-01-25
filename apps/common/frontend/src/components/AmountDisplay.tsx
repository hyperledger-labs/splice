// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import BigNumber from 'bignumber.js';

import { Unit } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';

import { unitToCurrency } from '../utils/helpers';

type AmountDisplayProps = {
  amount?: number | string | BigNumber;
  currency: Unit;
  convert?: Conversion;
  amuletPrice?: number | BigNumber;
};

const AmountDisplay: React.FC<AmountDisplayProps> = props => {
  let _amount: BigNumber,
    _currency: string = unitToCurrency(props.currency);

  if (props.amount === undefined) {
    return <>--.-- {props.currency}</>;
  }

  if (typeof props.amount === 'number' || typeof props.amount === 'string') {
    _amount = BigNumber(props.amount);
  } else {
    _amount = props.amount;
  }

  if (props.convert && props.amuletPrice) {
    switch (true) {
      case props.currency === 'AmuletUnit' && props.convert === 'CCtoUSD':
        _amount = _amount.multipliedBy(props.amuletPrice);
        _currency = 'USD';
        break;
      case props.currency === 'USDUnit' && props.convert === 'USDtoCC':
        _amount = _amount.div(props.amuletPrice);
        _currency = window.splice_config.spliceInstanceNames?.amuletNameAcronym;
        break;
      default:
        throw Error(
          `Conversion ${props.convert} not properly defined, currency: ${props.currency}`
        );
    }
  }

  return (
    <>
      {_amount.toFormat()} {_currency}
    </>
  );
};

export default AmountDisplay;
