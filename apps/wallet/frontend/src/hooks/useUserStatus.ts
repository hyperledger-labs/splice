// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useUserState } from '@lfdecentralizedtrust/splice-common-frontend';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

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
