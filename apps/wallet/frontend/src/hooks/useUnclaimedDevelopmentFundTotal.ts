// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { useValidatorScanProxyClient } from '../contexts/ValidatorScanProxyContext';
import { DEVELOPMENT_FUND_QUERY_KEYS } from '../constants/developmentFundQueryKeys';

interface UnclaimedDevelopmentFundCouponPayload {
  amount: string;
}

export const useUnclaimedDevelopmentFundTotal = () => {
  const scanClient = useValidatorScanProxyClient();
  const queryClient = useQueryClient();

  const { data, isLoading } = useQuery({
    queryKey: DEVELOPMENT_FUND_QUERY_KEYS.unclaimedTotal,
    queryFn: async () => {
      const response = await scanClient.listUnclaimedDevelopmentFundCoupons();
      const coupons = response.unclaimed_development_fund_coupons || [];

      return coupons.reduce((total, coupon) => {
        const payload = coupon.contract?.payload as UnclaimedDevelopmentFundCouponPayload | undefined;
        const amount = new BigNumber(payload?.amount || 0);
        return total.plus(amount);
      }, new BigNumber(0));
    },
  });

  const invalidate = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: DEVELOPMENT_FUND_QUERY_KEYS.unclaimedTotal });
  }, [queryClient]);

  return {
    data: data ?? new BigNumber(0),
    isLoading,
    invalidate,
  };
};
