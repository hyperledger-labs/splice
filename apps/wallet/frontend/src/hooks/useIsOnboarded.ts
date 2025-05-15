// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseQueryResult, useQuery } from '@tanstack/react-query';

import { useUserStatus } from './useUserStatus';

export const useIsOnboarded = (): UseQueryResult<boolean> => {
  const { data } = useUserStatus();

  return useQuery({
    queryKey: ['isOnboarded', data],
    queryFn: () => {
      const { userOnboarded, userWalletInstalled } = data!;
      return !!(userOnboarded && userWalletInstalled);
    },
  });
};
