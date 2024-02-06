import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';

import { useUserStatus } from './useUserStatus';

export const useIsOnboarded = (): UseQueryResult<boolean> => {
  const { data } = useUserStatus();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['isOnboarded', data],
    queryFn: () => {
      const { userOnboarded, userWalletInstalled } = data!;
      return !!(userOnboarded && userWalletInstalled);
    },
    enabled: !!data,
  });
};
