// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useIsDevelopmentFundManager } from './useIsDevelopmentFundManager';
import {
  useActiveDevelopmentFundCoupons,
  UseActiveDevelopmentFundCouponsResult,
} from './useActiveDevelopmentFundCoupons';
import {
  useDevelopmentFundCouponHistory,
  UseDevelopmentFundCouponHistoryResult,
} from './useDevelopmentFundCouponHistory';
import { useUnclaimedDevelopmentFundTotal } from './useUnclaimedDevelopmentFundTotal';
import { usePrimaryParty } from './usePrimaryParty';
import { invalidateAllDevelopmentFundQueries } from '../utils/invalidateDevelopmentFundQueries';
import BigNumber from 'bignumber.js';

type UseDevelopmentFundResult = {
  primaryParty: string | undefined;
  isFundManager: boolean;
  isLoading: boolean;
  coupons: UseActiveDevelopmentFundCouponsResult;
  history: UseDevelopmentFundCouponHistoryResult;
  unclaimedTotal: BigNumber;
  isLoadingUnclaimedTotal: boolean;
  isUnclaimedTotalError: boolean;
  unclaimedTotalError: Error | null;
  invalidateAll: () => void;
};

export const useDevelopmentFund = (): UseDevelopmentFundResult => {
  const primaryParty = usePrimaryParty();
  const { isFundManager, isLoading: isLoadingFundManager } = useIsDevelopmentFundManager();
  const couponsData = useActiveDevelopmentFundCoupons(primaryParty);
  const historyData = useDevelopmentFundCouponHistory();
  const unclaimedTotalData = useUnclaimedDevelopmentFundTotal();
  const queryClient = useQueryClient();

  const isLoading =
    isLoadingFundManager ||
    couponsData.isLoading ||
    historyData.isLoadingHistory ||
    unclaimedTotalData.isLoading;

  const invalidateAll = React.useCallback(() => {
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
    isUnclaimedTotalError: unclaimedTotalData.isError,
    unclaimedTotalError: unclaimedTotalData.error,
    invalidateAll,
  };
};
