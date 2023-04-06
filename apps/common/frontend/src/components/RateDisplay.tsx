import * as React from 'react';
import BigNumber from 'bignumber.js';

type RateDisplayProps = {
  base: Currency;
  quote: Currency;
  coinPrice: number | BigNumber;
};

const RateDisplay: React.FC<RateDisplayProps> = props => {
  var { base, quote, coinPrice } = props;
  var amount, rate;
  switch (true) {
    case base === 'CC' && quote === 'USD':
      amount = BigNumber(1).div(coinPrice);
      rate = 'CC/USD';
      break;
    case base === 'USD' && quote === 'CC':
      amount = coinPrice;
      rate = 'USD/CC';
      break;
    default:
      throw Error('Rate not properly defined.');
  }
  return (
    <>
      {amount.toString()} {rate}
    </>
  );
};

export default RateDisplay;
