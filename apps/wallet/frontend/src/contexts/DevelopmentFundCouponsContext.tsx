// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { createContext, useContext } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useActiveDevelopmentFundCoupons } from '../hooks/useActiveDevelopmentFundCoupons';
import { useIsDevelopmentFundManager } from '../hooks/useIsDevelopmentFundManager';
import { useValidatorScanProxyClient } from './ValidatorScanProxyContext';
import { DevelopmentFundCoupon } from '../models/models';
import BigNumber from 'bignumber.js';

interface UnclaimedDevelopmentFundCouponPayload {
  amount: string;
}

interface DevelopmentFundContextValue {
  // Fund manager status
  isFundManager: boolean;
  // Coupons data
  coupons: DevelopmentFundCoupon[];
  totalAmount: BigNumber;
  // Unclaimed total from scan proxy
  unclaimedTotal: BigNumber;
  isLoadingUnclaimedTotal: boolean;
  isLoading: boolean;
  isError: boolean;
  error: Error | null;
  hasNextPage: boolean;
  hasPreviousPage: boolean;
  currentPage: number;
  goToNextPage: () => void;
  goToPreviousPage: () => void;
  invalidate: () => void;
}

const DevelopmentFundContext = createContext<DevelopmentFundContextValue | undefined>(undefined);

export const DevelopmentFundProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const { isFundManager, isLoading: isLoadingFundManager } = useIsDevelopmentFundManager();
  const couponsData = useActiveDevelopmentFundCoupons();
  const scanClient = useValidatorScanProxyClient();

  // Fetch unclaimed total from scan proxy
  const { data: unclaimedTotal, isLoading: isLoadingUnclaimedTotal } = useQuery({
    queryKey: ['scan-api', 'listUnclaimedDevelopmentFundCoupons', 'total'],
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

  const value: DevelopmentFundContextValue = {
    isFundManager,
    ...couponsData,
    unclaimedTotal: unclaimedTotal || new BigNumber(0),
    isLoadingUnclaimedTotal,
    isLoading: isLoadingFundManager || couponsData.isLoading,
  };

  return (
    <DevelopmentFundContext.Provider value={value}>{children}</DevelopmentFundContext.Provider>
  );
};

export const useDevelopmentFundContext = (): DevelopmentFundContextValue => {
  const context = useContext(DevelopmentFundContext);
  if (!context) {
    throw new Error('useDevelopmentFundContext must be used within a DevelopmentFundProvider');
  }
  return context;
};
