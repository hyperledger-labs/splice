import { useScanClient, useUserState } from 'common-frontend';
import DevNetOnly from 'common-frontend/lib/components/DevNetOnly';
import React, { useEffect, useState } from 'react';

import { Star } from '@mui/icons-material';
import { Button, Tooltip } from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';

const FeaturedAppRight: React.FC = () => {
  const { primaryPartyId } = useUserState();
  const { lookupFeaturedAppRight } = useScanClient();
  const { selfGrantFeaturedAppRights } = useWalletClient();

  const [featured, setFeatured] = useState<boolean | undefined>(undefined);
  useEffect(() => {
    const getFeatured = async () => {
      if (primaryPartyId) {
        const featured = await lookupFeaturedAppRight(primaryPartyId);
        setFeatured(!!featured); // HTTP api might return null
      }
    };
    getFeatured();
  }, [lookupFeaturedAppRight, primaryPartyId]);

  if (!primaryPartyId || featured === undefined) {
    // Loading
    return <></>;
  }

  const selfGrant = () => {
    selfGrantFeaturedAppRights().then(
      () => {
        setFeatured(true);
      },
      err => console.error('Failed to self-grant featured app rights.', err)
    );
  };

  if (featured) {
    return (
      // not-so-fun fact: setting `id` in Tooltip does nothing.
      <Tooltip title={'FEATURED'}>
        <Star id="featured-status" />
      </Tooltip>
    );
  } else {
    return (
      <DevNetOnly>
        <Button variant="contained" color="info" onClick={selfGrant} id="self-feature">
          Self-grant featured app rights
        </Button>
      </DevNetOnly>
    );
  }
};

export default FeaturedAppRight;
