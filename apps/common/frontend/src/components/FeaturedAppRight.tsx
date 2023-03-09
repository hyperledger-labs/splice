import React, { useEffect, useState } from 'react';

import { useScanClient } from '../contexts/ScanServiceContext';

const FeaturedAppRight: React.FC<{ partyId: string }> = ({ partyId }) => {
  const scanClient = useScanClient();

  const [featured, setFeatured] = useState<boolean | undefined>(undefined);
  useEffect(() => {
    const getFeatured = async () => {
      const featured = await scanClient.lookupFeaturedAppRight(partyId);
      setFeatured(!!featured); // HTTP api might return null
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
