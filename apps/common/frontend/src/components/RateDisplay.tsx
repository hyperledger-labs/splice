import * as React from 'react';
import BigNumber from 'bignumber.js';

type RateDisplayProps = {
  base: Currency;
  quote: Currency;
  amuletPrice: number | BigNumber;
};

const RateDisplay: React.FC<RateDisplayProps> = props => {
  var { base, quote, amuletPrice } = props;
  var amount, rate;
  switch (true) {
    case base === 'CC' && quote === 'USD':
      amount = BigNumber(1).div(amuletPrice);
      rate = 'CC/USD';
      break;
    case base === 'USD' && quote === 'CC':
      amount = amuletPrice;
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
