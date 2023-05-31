import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { useUserState } from 'common-frontend';

import { useWalletClient, UserStatusResponse } from '../contexts/WalletServiceContext';

export const useUserStatus = (): UseQueryResult<UserStatusResponse> => {
  const { userStatus } = useWalletClient();
  const { isAuthenticated } = useUserState();

  return useQuery({
    queryKey: ['user-status', isAuthenticated],
    queryFn: userStatus,
    enabled: !!isAuthenticated,
  });
};
