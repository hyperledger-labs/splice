// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { FeaturedAppConfig, FeaturedAppRight } from '@daml.js/splice-amulet/lib/Splice/Amulet';
import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

// Fetches the selected FeaturedAppRight's current config (appKind / activityWeight / opsParties).
// Used to prepopulate the amend editor and as the amend baseConfig. Fires only once a right is selected.
export const useFeaturedAppRightConfig = (rightCid: string): UseQueryResult<FeaturedAppConfig> => {
  const svAdminClient = useSvAdminClient();
  return useQuery({
    queryKey: ['featuredAppRightConfig', rightCid],
    enabled: !!rightCid,
    queryFn: async (): Promise<FeaturedAppConfig> => {
      const response = await svAdminClient.lookupFeaturedAppRightByContractId(rightCid);
      const payload = response.featured_app_right?.payload as FeaturedAppRight | undefined;
      return {
        appKind: payload?.appKind ?? null,
        activityWeight: payload?.activityWeight ?? null,
        opsParties: payload?.opsParties ?? null,
      };
    },
  });
};
