// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import useGetAmuletRules from './scan-proxy/useGetAmuletRules';
import { usePrimaryParty } from './usePrimaryParty';

interface UseIsDevelopmentFundManagerResult {
  isFundManager: boolean;
  isLoading: boolean;
}

export const useIsDevelopmentFundManager = (): UseIsDevelopmentFundManagerResult => {
  const { data: amuletRulesData, isLoading } = useGetAmuletRules();
  const primaryParty = usePrimaryParty();

  const optDevelopmentFundManager =
    amuletRulesData?.contract.payload.configSchedule.initialValue.optDevelopmentFundManager;

  const isFundManager =
    !!primaryParty && !!optDevelopmentFundManager && primaryParty === optDevelopmentFundManager;

  return { isFundManager, isLoading };
};
