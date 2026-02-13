// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';

const PAGE_SIZE = 10;

export const useDevelopmentFundCouponHistory = () => {
  const { listCouponHistoryEvents } = useWalletClient();
  const queryClient = useQueryClient();

  const [offset, setOffset] = React.useState<number>(0);

  const historyQuery = useQuery({
    queryKey: ['developmentFundCouponHistory', offset, PAGE_SIZE],
    queryFn: () => listCouponHistoryEvents(PAGE_SIZE, offset),
  });

  const total = historyQuery.data?.total || 0;
  const events = historyQuery.data?.events || [];

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
    queryClient.invalidateQueries({ queryKey: ['developmentFundCouponHistory'] });
  }, [queryClient]);

  return {
    events,
    total,
    isLoading: historyQuery.isLoading,
    isError: historyQuery.isError,
    error: historyQuery.error,
    hasNextPage,
    hasPreviousPage,
    currentPage,
    goToNextPage,
    goToPreviousPage,
    invalidate,
  };
};
