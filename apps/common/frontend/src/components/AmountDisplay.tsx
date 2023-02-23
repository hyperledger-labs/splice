import * as React from 'react';

interface AmountDisplayProps {
  amount?: string;
  currency?: string;
}

const AmountDisplay: React.FC<AmountDisplayProps> = props => {
  if (props.amount) {
    return (
      <>
        {props.amount} {props.currency || 'CC'}
      </>
    );
  } else {
    return <>...</>;
  }
};

export default AmountDisplay;
