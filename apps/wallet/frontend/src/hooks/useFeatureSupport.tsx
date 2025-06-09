// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';

interface WalletFeatureSupport {
  tokenStandard: boolean;
  transferPreapprovalDescription: boolean;
}
export const useFeatureSupport = (): UseQueryResult<WalletFeatureSupport> => {
  const walletClient = useWalletClient();
  return useQuery({
    queryKey: ['featureSupport'],
    queryFn: async () => {
      const result = await walletClient.featureSupport();
      return {
        tokenStandard: result.token_standard,
        transferPreapprovalDescription: result.transfer_preapproval_description,
      };
    },
  });
};
