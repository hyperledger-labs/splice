// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { DEVELOPMENT_FUND_QUERY_KEYS } from '../constants/developmentFundQueryKeys';
import { DevelopmentFundCoupon } from '../models/models';

const PAGE_SIZE = 10;

export const useActiveDevelopmentFundCoupons = (fundManager?: string) => {
  const { listActiveDevelopmentFundCoupons } = useWalletClient();
  const queryClient = useQueryClient();

  const [currentPage, setCurrentPage] = React.useState<number>(1);

  const couponsQuery = useQuery({
    queryKey: DEVELOPMENT_FUND_QUERY_KEYS.activeCoupons,
    queryFn: () => listActiveDevelopmentFundCoupons(),
  });

  const allCoupons = couponsQuery.data || [];
  const filteredCoupons = React.useMemo(() => {
    if (!fundManager) return allCoupons;
    return allCoupons.filter((c: DevelopmentFundCoupon) => c.fundManager === fundManager);
  }, [allCoupons, fundManager]);

  const sortedCoupons = React.useMemo(
    () =>
      [...filteredCoupons].sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime()),
    [filteredCoupons]
  );

  const totalCount = sortedCoupons.length;
  const offset = (currentPage - 1) * PAGE_SIZE;
  const coupons = sortedCoupons.slice(offset, offset + PAGE_SIZE);

  const goToNextPage = React.useCallback(() => {
    if (offset + PAGE_SIZE < totalCount) {
      setCurrentPage(prev => prev + 1);
    }
  }, [offset, totalCount]);

  const goToPreviousPage = React.useCallback(() => {
    if (currentPage > 1) {
      setCurrentPage(prev => prev - 1);
    }
  }, [currentPage]);

  const hasNextPage = offset + PAGE_SIZE < totalCount;
  const hasPreviousPage = currentPage > 1;

  const invalidate = React.useCallback(() => {
    queryClient.invalidateQueries({ queryKey: DEVELOPMENT_FUND_QUERY_KEYS.activeCoupons });
  }, [queryClient]);

  return {
    coupons,
    isLoading: couponsQuery.isLoading,
    isError: couponsQuery.isError,
    error: couponsQuery.error,
    hasNextPage,
    hasPreviousPage,
    currentPage,
    goToNextPage,
    goToPreviousPage,
    invalidate,
  };
};
