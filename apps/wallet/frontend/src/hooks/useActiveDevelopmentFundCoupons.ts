// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';
import BigNumber from 'bignumber.js';

const PAGE_SIZE = 10;

export const useActiveDevelopmentFundCoupons = () => {
  const { listActiveDevelopmentFundCoupons } = useWalletClient();
  const queryClient = useQueryClient();

  const [currentPage, setCurrentPage] = React.useState<number>(1);

  const couponsQuery = useQuery({
    queryKey: ['activeDevelopmentFundCoupons'],
    queryFn: () => listActiveDevelopmentFundCoupons(),
  });

  const allCoupons = couponsQuery.data || [];
  const totalCount = allCoupons.length;
  const totalAmount = allCoupons.reduce((sum, c) => sum.plus(c.amount), new BigNumber(0));

  const sortedCoupons = React.useMemo(
    () => [...allCoupons].sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime()),
    [allCoupons]
  );

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
    queryClient.invalidateQueries({ queryKey: ['activeDevelopmentFundCoupons'] });
  }, [queryClient]);

  return {
    coupons,
    totalAmount,
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
