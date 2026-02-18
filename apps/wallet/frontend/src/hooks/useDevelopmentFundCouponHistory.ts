// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useCallback, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
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
  invalidateHistory: () => void;
}

export const useDevelopmentFundCouponHistory = (
  fundManager?: string
): UseDevelopmentFundCouponHistoryResult => {
  const { listCouponHistoryEvents } = useWalletClient();
  const queryClient = useQueryClient();

  const [cursorStack, setCursorStack] = useState<(number | undefined)[]>([undefined]);
  const currentCursor = cursorStack[cursorStack.length - 1];

  const historyQuery = useQuery({
    queryKey: [...DEVELOPMENT_FUND_QUERY_KEYS.couponHistory, currentCursor, PAGE_SIZE],
    queryFn: () => listCouponHistoryEvents(PAGE_SIZE, currentCursor),
  });

  const rawEvents = historyQuery.data?.events || [];
  const events = useMemo(() => {
    if (!fundManager) return rawEvents;
    return rawEvents.filter((e: CouponHistoryEvent) => e.fundManager === fundManager);
  }, [rawEvents, fundManager]);
  const nextPageToken = historyQuery.data?.nextPageToken;

  const goToNextHistoryPage = useCallback(() => {
    if (nextPageToken !== undefined && rawEvents.length === PAGE_SIZE) {
      setCursorStack(prev => [...prev, nextPageToken]);
    }
  }, [nextPageToken, rawEvents.length]);

  const goToPreviousHistoryPage = useCallback(() => {
    if (cursorStack.length > 1) {
      setCursorStack(prev => prev.slice(0, -1));
    }
  }, [cursorStack.length]);

  const hasNextHistoryPage =
    nextPageToken !== undefined && rawEvents.length === PAGE_SIZE;
  const hasPreviousHistoryPage = cursorStack.length > 1;
  const currentHistoryPage = cursorStack.length;

  const invalidateHistory = useCallback(() => {
    setCursorStack([undefined]);
    queryClient.invalidateQueries({ queryKey: DEVELOPMENT_FUND_QUERY_KEYS.couponHistory });
  }, [queryClient]);

  return {
    historyEvents: events,
    isLoadingHistory: historyQuery.isLoading,
    isHistoryError: historyQuery.isError,
    historyError: historyQuery.error,
    hasNextHistoryPage,
    hasPreviousHistoryPage,
    currentHistoryPage,
    goToNextHistoryPage,
    goToPreviousHistoryPage,
    invalidateHistory,
  };
};
