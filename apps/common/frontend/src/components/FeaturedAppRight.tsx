import { LookupFeaturedAppRightRequest } from 'common-protobuf/com/daml/network/scan/v0/scan_service_pb';
import React, { useEffect, useState } from 'react';

import { useScanClient } from '../contexts/ScanServiceContext';

const FeaturedAppRight: React.FC<{ partyId: string }> = ({ partyId }) => {
  const scanClient = useScanClient();

  const [featured, setFeatured] = useState<boolean | undefined>(undefined);
  useEffect(() => {
    const getFeatured = async () => {
      const request = new LookupFeaturedAppRightRequest();
      request.setProviderPartyId(partyId);
      const value = await scanClient.lookupFeaturedAppRight(request);
      const featured = value.getFeaturedAppRight();
      setFeatured(featured !== undefined);
    };
    getFeatured();
  }, [scanClient, partyId]);

  if (featured === undefined) {
    return <div id="featured-status">...</div>;
  }

  if (featured) {
    return <div id="featured-status">FEATURED</div>;
  } else {
    return <div id="featured-status"></div>;
  }
};

export default FeaturedAppRight;
