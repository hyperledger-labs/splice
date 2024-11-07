// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// TODO(#8515) - reuse this from wallet UI
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { useUserState } from 'common-frontend';
import { PollingStrategy } from 'common-frontend-utils';

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
