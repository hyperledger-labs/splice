import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { UserStatusResponse, useUserState } from 'common-frontend';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useUserStatus = (): UseQueryResult<UserStatusResponse> => {
  const { userStatus } = useWalletClient();
  const { isAuthenticated, updateStatus } = useUserState();

  return useQuery({
    queryKey: ['user-status', isAuthenticated, updateStatus],
    queryFn: async () => {
      const status = await userStatus();
      updateStatus(status);
      return status;
    },
    enabled: !!isAuthenticated,
  });
};
