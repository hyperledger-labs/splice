// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { CouponHistoryEvent } from '../models/models';
import { DEVELOPMENT_FUND_QUERY_KEYS } from '../constants/developmentFundQueryKeys';

const PAGE_SIZE = 10;

export interface UseDevelopmentFundCouponHistoryResult {
  historyEvents: CouponHistoryEvent[];
  isLoadingHistory: boolean;
  isHistoryError: boolean;
  historyError: Error | null;
  hasNextHistoryPage: boolean;
  hasPreviousHistoryPage: boolean;
  currentHistoryPage: number;
  goToNextHistoryPage: () => void;
  goToPreviousHistoryPage: () => void;
}

export const useDevelopmentFundCouponHistory = (): UseDevelopmentFundCouponHistoryResult => {
  const { listCouponHistoryEvents } = useWalletClient();

  const [cursorStack, setCursorStack] = React.useState<(number | undefined)[]>([undefined]);
  const currentCursor = cursorStack[cursorStack.length - 1];

  const historyQuery = useQuery({
    queryKey: [...DEVELOPMENT_FUND_QUERY_KEYS.couponHistory, currentCursor],
    queryFn: () => listCouponHistoryEvents(PAGE_SIZE, currentCursor),
  });

  const historyEvents = historyQuery.data?.events || [];
  const nextPageToken = historyQuery.data?.nextPageToken;

  const goToNextHistoryPage = React.useCallback(() => {
    if (nextPageToken !== undefined && historyEvents.length === PAGE_SIZE) {
      setCursorStack(prev => [...prev, nextPageToken]);
    }
  }, [nextPageToken, historyEvents.length]);

  const goToPreviousHistoryPage = React.useCallback(() => {
    if (cursorStack.length > 1) {
      setCursorStack(prev => prev.slice(0, -1));
    }
  }, [cursorStack.length]);

  const hasNextHistoryPage = nextPageToken !== undefined && historyEvents.length === PAGE_SIZE;
  const hasPreviousHistoryPage = cursorStack.length > 1;
  const currentHistoryPage = cursorStack.length;

  return {
    historyEvents,
    isLoadingHistory: historyQuery.isLoading,
    isHistoryError: historyQuery.isError,
    historyError: historyQuery.error,
    hasNextHistoryPage,
    hasPreviousHistoryPage,
    currentHistoryPage,
    goToNextHistoryPage,
    goToPreviousHistoryPage,
  };
};
