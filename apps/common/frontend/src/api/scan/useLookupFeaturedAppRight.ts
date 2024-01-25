import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { LookupFeaturedAppRightResponse } from 'scan-openapi';

import { FeaturedAppRight } from '@daml.js/canton-coin/lib/CC/Coin/';

import { Contract, PollingStrategy } from '../../utils';
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
    refetchInterval: PollingStrategy.FIXED,
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
