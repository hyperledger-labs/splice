// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery } from '@tanstack/react-query';
import { useValidatorScanProxyClient } from '../contexts/ValidatorScanProxyContext';
import { usePrimaryParty } from './usePrimaryParty';

interface UseIsDevelopmentFundManagerResult {
  isFundManager: boolean;
  isLoading: boolean;
}

interface AmuletRulesPayload {
  configSchedule?: {
    initialValue?: {
      optDevelopmentFundManager?: string | null;
    };
  };
}

export const useIsDevelopmentFundManager = (): UseIsDevelopmentFundManagerResult => {
  const scanClient = useValidatorScanProxyClient();
  const primaryParty = usePrimaryParty();

  const { data: dsoInfo, isLoading } = useQuery({
    queryKey: ['getDsoInfo'],
    queryFn: async () => {
      const resp = await scanClient.getDsoInfo();
      return resp;
    },
  });

  const payload = dsoInfo?.amulet_rules?.contract?.payload as AmuletRulesPayload | undefined;
  const optDevelopmentFundManager = payload?.configSchedule?.initialValue?.optDevelopmentFundManager;

  const isFundManager =
    !!primaryParty && !!optDevelopmentFundManager && primaryParty === optDevelopmentFundManager;

  return { isFundManager, isLoading };
};
