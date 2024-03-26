import BigNumber from 'bignumber.js';
import { ErrorDisplay, Loading } from 'common-frontend';
import { useTotalAmuletBalance } from 'common-frontend/scan-api';

import AmountSummary from './AmountSummary';

export const TotalAmuletBalance: React.FC = () => {
  const totalAmuletBalanceQuery = useTotalAmuletBalance();

  switch (totalAmuletBalanceQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message={'Could not retrieve total amulet balance'} />;
    case 'success':
      return (
        <AmountSummary
          title="Total Amulet Balance"
          amount={new BigNumber(totalAmuletBalanceQuery.data.total_balance)}
          idCC="total-amulet-balance-cc"
        />
      );
  }
};

export default TotalAmuletBalance;
