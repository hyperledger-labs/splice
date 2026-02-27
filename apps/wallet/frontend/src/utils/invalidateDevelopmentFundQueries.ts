// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { QueryClient } from '@tanstack/react-query';
import { DEVELOPMENT_FUND_QUERY_KEYS } from '../constants/developmentFundQueryKeys';

export const invalidateAllDevelopmentFundQueries = (queryClient: QueryClient): Promise<void> => {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: DEVELOPMENT_FUND_QUERY_KEYS.activeCoupons }),
    queryClient.invalidateQueries({ queryKey: DEVELOPMENT_FUND_QUERY_KEYS.couponHistory }),
    queryClient.invalidateQueries({ queryKey: DEVELOPMENT_FUND_QUERY_KEYS.unclaimedTotal }),
  ]).then(() => undefined);
};
