// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';

const PAGE_SIZE = 10;

export const useDevelopmentFundCouponsHistory = () => {
  const { listDevelopmentFundCouponsHistory } = useWalletClient();
  const queryClient = useQueryClient();

  const [offset, setOffset] = React.useState<number>(0);

  const couponsQuery = useQuery({
    queryKey: ['developmentFundCoupons', offset, PAGE_SIZE],
    queryFn: () => listDevelopmentFundCouponsHistory(PAGE_SIZE, offset),
  });

  const total = couponsQuery.data?.total || 0;
  const coupons = couponsQuery.data?.coupons || [];

  const goToNextPage = React.useCallback(() => {
    if (offset + PAGE_SIZE < total) {
      setOffset(prev => prev + PAGE_SIZE);
    }
  }, [offset, total]);

  const goToPreviousPage = React.useCallback(() => {
    if (offset > 0) {
      setOffset(prev => Math.max(0, prev - PAGE_SIZE));
    }
  }, [offset]);

  const hasNextPage = offset + PAGE_SIZE < total;
  const hasPreviousPage = offset > 0;
  const currentPage = Math.floor(offset / PAGE_SIZE) + 1;

  const invalidate = React.useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['developmentFundCoupons'] });
    queryClient.invalidateQueries({ queryKey: ['developmentFundTotal'] });
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
