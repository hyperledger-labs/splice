import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { FeaturedAppRight } from '../../../daml.js/canton-coin-0.1.0/lib/CC/Coin';
import { Contract } from '../../utils';
import { useScanClient } from './ScanClientContext';

const useLookupFeaturedAppRight = (
  primaryPartyId?: string
): UseQueryResult<Contract<FeaturedAppRight> | undefined> => {
  const scanClient = useScanClient();

  return useQuery({
    queryKey: ['lookupFeaturedAppRight', primaryPartyId, FeaturedAppRight],
    queryFn: async () => {
      const response = await scanClient.lookupFeaturedAppRight(primaryPartyId!);

      return (
        response.featuredAppRight &&
        Contract.decodeOpenAPI(response.featuredAppRight, FeaturedAppRight)
      );
    },
    enabled: !!primaryPartyId,
  });
};

export default useLookupFeaturedAppRight;
