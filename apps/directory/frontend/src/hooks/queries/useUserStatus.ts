// TODO(#8515) - reuse this from wallet UI
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy, useUserState } from 'common-frontend';

import { useWalletClient, UserStatusResponse } from '../../context/WalletServiceContext';

export const useUserStatus = (): UseQueryResult<UserStatusResponse> => {
  const { userStatus } = useWalletClient();
  const { isAuthenticated } = useUserState();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['user-status', isAuthenticated],
    queryFn: userStatus,
    enabled: !!isAuthenticated,
  });
};
