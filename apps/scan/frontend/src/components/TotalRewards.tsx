import BigNumber from 'bignumber.js';
import { ErrorDisplay, Loading } from 'common-frontend';
import { useTotalRewards } from 'common-frontend/scan-api';

import AmountSummary from './AmountSummary';

export const TotalRewards: React.FC = () => {
  const totalRewardsQuery = useTotalRewards();

  switch (totalRewardsQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message={'Could not retrieve total rewards'} />;
    case 'success':
      return (
        <AmountSummary
          title="Total App & Validator Rewards"
          amount={new BigNumber(totalRewardsQuery.data.amount)}
          idCC="total-rewards-cc"
        />
      );
  }
};

export default TotalRewards;
