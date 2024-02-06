import { UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';
import { useLookupFeaturedAppRightBuilder } from 'common-frontend/scan-api';

import { FeaturedAppRight } from '@daml.js/canton-coin/lib/CC/Coin/';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupFeaturedAppRight = (
  primaryPartyId?: string
): UseQueryResult<Contract<FeaturedAppRight> | undefined> => {
  const scanClient = useValidatorScanProxyClient();

  return useLookupFeaturedAppRightBuilder(
    () => scanClient.lookupFeaturedAppRight(primaryPartyId!),
    primaryPartyId
  );
};

export default useLookupFeaturedAppRight;
