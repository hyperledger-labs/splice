import { DevNetOnly } from 'common-frontend';
import { useLookupFeaturedAppRight } from 'common-frontend/scan-api';
import React from 'react';

import { Star } from '@mui/icons-material';
import { Button, Tooltip } from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';

const FeaturedAppRight: React.FC = () => {
  const { selfGrantFeaturedAppRights } = useWalletClient();
  const { isLoading: featuredQueryLoading, data: featured } = useLookupFeaturedAppRight();

  if (featuredQueryLoading) {
    return <></>;
  }

  const selfGrant = () => {
    selfGrantFeaturedAppRights().catch(err =>
      console.error('Failed to self-grant featured app rights.', err)
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
