// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { SvInfo } from '@canton-network/splice-common-frontend';
import { useScanClient } from '@canton-network/splice-common-frontend/scan-api';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

export const useSvInfo = (): UseQueryResult<SvInfo> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['getSvInfo'],
    queryFn: async () => {
      const resp = await scanClient.getSvInfo();
      return {
        svUser: resp.sv_user,
        svPartyId: resp.sv_party_id,
      };
    },
  });
};
