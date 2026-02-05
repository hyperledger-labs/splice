// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { createContext, useContext } from 'react';
import { useActiveDevelopmentFundCoupons } from '../hooks/useActiveDevelopmentFundCoupons';
import { useIsDevelopmentFundManager } from '../hooks/useIsDevelopmentFundManager';
import { DevelopmentFundCoupon } from '../models/models';
import BigNumber from 'bignumber.js';

interface DevelopmentFundContextValue {
  // Fund manager status
  isFundManager: boolean;
  // Coupons data
  coupons: DevelopmentFundCoupon[];
  totalAmount: BigNumber;
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

  const value: DevelopmentFundContextValue = {
    isFundManager,
    ...couponsData,
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
