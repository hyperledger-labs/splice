// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useIsDevelopmentFundManager } from './useIsDevelopmentFundManager';
import { useActiveDevelopmentFundCoupons } from './useActiveDevelopmentFundCoupons';
import { useDevelopmentFundCouponHistory } from './useDevelopmentFundCouponHistory';
import { useUnclaimedDevelopmentFundTotal } from './useUnclaimedDevelopmentFundTotal';
import { usePrimaryParty } from './usePrimaryParty';
import { invalidateAllDevelopmentFundQueries } from '../utils/invalidateDevelopmentFundQueries';

export const useDevelopmentFund = () => {
  const primaryParty = usePrimaryParty();
  const { isFundManager, isLoading: isLoadingFundManager } = useIsDevelopmentFundManager();
  const couponsData = useActiveDevelopmentFundCoupons(primaryParty);
  const historyData = useDevelopmentFundCouponHistory(primaryParty);
  const unclaimedTotalData = useUnclaimedDevelopmentFundTotal();
  const queryClient = useQueryClient();

  const isLoading =
    isLoadingFundManager ||
    couponsData.isLoading ||
    historyData.isLoadingHistory ||
    unclaimedTotalData.isLoading;

  const invalidateAll = useCallback(() => {
    return invalidateAllDevelopmentFundQueries(queryClient);
  }, [queryClient]);

  return {
    primaryParty,
    isFundManager,
    isLoading,
    coupons: couponsData,
    history: historyData,
    unclaimedTotal: unclaimedTotalData.data,
    isLoadingUnclaimedTotal: unclaimedTotalData.isLoading,
    invalidateAll,
  };
};
