// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import { Star } from '@mui/icons-material';
import { Button, Tooltip } from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { usePrimaryParty } from '../hooks';
import useLookupFeaturedAppRight from '../hooks/scan-proxy/useLookupFeaturedAppRight';
import DevNetOnly from './DevNetOnly';

const FeaturedAppRight: React.FC = () => {
  const { selfGrantFeaturedAppRights } = useWalletClient();
  const primaryPartyId = usePrimaryParty();
  const { isLoading: featuredQueryLoading, data: featured } =
    useLookupFeaturedAppRight(primaryPartyId);

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
        <Button
          variant="contained"
          color="info"
          onClick={selfGrant}
          id="self-feature"
          sx={{ textWrap: 'balance' }}
        >
          Self-grant featured app rights
        </Button>
      </DevNetOnly>
    );
  }
};

export default FeaturedAppRight;
