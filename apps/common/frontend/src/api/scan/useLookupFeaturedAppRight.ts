import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { FeaturedAppRight } from '@daml.js/canton-coin/lib/CC/Coin/';

import { Contract, PollingStrategy } from '../../utils';
import { useScanClient } from './ScanClientContext';

const useLookupFeaturedAppRight = (
  primaryPartyId?: string
): UseQueryResult<Contract<FeaturedAppRight> | undefined> => {
  const scanClient = useScanClient();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['scan-api', 'lookupFeaturedAppRight', primaryPartyId, FeaturedAppRight],
    queryFn: async () => {
      const response = await scanClient.lookupFeaturedAppRight(primaryPartyId!);

      return (
        response.featured_app_right &&
        Contract.decodeOpenAPI(response.featured_app_right, FeaturedAppRight)
      );
    },
    enabled: !!primaryPartyId,
  });
};

export default useLookupFeaturedAppRight;
