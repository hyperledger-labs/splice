import * as React from 'react';
import BigNumber from 'bignumber.js';

type AmountDisplayProps = {
  amount?: number | string | BigNumber;
  currency: Currency;
  convert?: Conversion;
  coinPrice?: number | BigNumber;
};

const AmountDisplay: React.FC<AmountDisplayProps> = props => {
  var _amount: BigNumber,
    _currency: Currency = props.currency;

  if (props.amount === undefined) {
    return <>--.-- {props.currency}</>;
  }

  if (typeof props.amount === 'number' || typeof props.amount === 'string') {
    _amount = BigNumber(props.amount);
  } else {
    _amount = props.amount;
  }

  if (props.convert && props.coinPrice) {
    switch (true) {
      case props.currency === 'CC' && props.convert === 'CCtoUSD':
        _amount = _amount.multipliedBy(props.coinPrice);
        _currency = 'USD';
        break;
      case props.currency === 'USD' && props.convert === 'USDtoCC':
        _amount = _amount.div(props.coinPrice);
        _currency = 'CC';
        break;
      default:
        throw Error('Conversion ' + props.convert + ' not properly defined.');
    }
  }

  return (
    <>
      {_amount.toString()} {_currency}
    </>
  );
};

export default AmountDisplay;
