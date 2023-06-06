import * as React from 'react';
import BigNumber from 'bignumber.js';

type AmountDisplayProps = {
  amount?: BigNumber;
  currency: Currency;
  convert?: Conversion;
  coinPrice?: number | BigNumber;
};

const AmountDisplay: React.FC<AmountDisplayProps> = props => {
  var { amount, currency } = props;

  if (!amount) {
    return <>--.-- {currency}</>;
  }

  if (props.convert && props.coinPrice) {
    switch (true) {
      case currency === 'CC' && props.convert === 'CCtoUSD':
        amount = amount.multipliedBy(props.coinPrice);
        currency = 'USD';
        break;
      case currency === 'USD' && props.convert === 'USDtoCC':
        amount = amount.div(props.coinPrice);
        currency = 'CC';
        break;
      default:
        throw Error('Conversion ' + props.convert + ' not properly defined.');
    }
  }
  return (
    <>
      {amount.toString()} {currency}
    </>
  );
};

export default AmountDisplay;
