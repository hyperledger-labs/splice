import BigNumber from 'bignumber.js';
import { ErrorDisplay, Loading } from 'common-frontend';
import { useTotalCoinBalance } from 'common-frontend/scan-api';

import AmountSummary from './AmountSummary';

export const TotalCoinBalance: React.FC = () => {
  const totalCoinBalanceQuery = useTotalCoinBalance();

  switch (totalCoinBalanceQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message={'Could not retrieve total coin balance'} />;
    case 'success':
      return (
        <AmountSummary
          title="Total Coin Balance"
          amount={new BigNumber(totalCoinBalanceQuery.data.total_balance)}
          idCC="total-coin-balance-cc"
        />
      );
  }
};

export default TotalCoinBalance;
