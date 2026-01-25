// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { LookupFeaturedAppRightResponse } from '@lfdecentralizedtrust/scan-openapi';

import { FeaturedAppRight } from '@daml.js/splice-amulet/lib/Splice/Amulet/';

import { useScanClient } from './ScanClientContext';

const useLookupFeaturedAppRight = (
  primaryPartyId?: string
): UseQueryResult<Contract<FeaturedAppRight> | undefined> => {
  const scanClient = useScanClient();

  return useLookupFeaturedAppRightBuilder(
    () => scanClient.lookupFeaturedAppRight(primaryPartyId!),
    primaryPartyId
  );
};

export function useLookupFeaturedAppRightBuilder(
  getResult: () => Promise<LookupFeaturedAppRightResponse>,
  primaryPartyId?: string
): UseQueryResult<Contract<FeaturedAppRight> | undefined> {
  return useQuery({
    queryKey: ['scan-api', 'lookupFeaturedAppRight', primaryPartyId, FeaturedAppRight],
    queryFn: async () => {
      const response = await getResult();

      return (
        response.featured_app_right &&
        Contract.decodeOpenAPI(response.featured_app_right, FeaturedAppRight)
      );
    },
    enabled: !!primaryPartyId,
  });
}

export default useLookupFeaturedAppRight;
