import { useScanClient } from 'common-frontend';
import { useUserState } from 'common-frontend';
import { LookupFeaturedAppRightRequest } from 'common-protobuf/com/daml/network/scan/v0/scan_service_pb';
import React, { useEffect, useState } from 'react';

import { Button } from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';

const SelfFeatureButton: React.FC = () => {
  const walletClient = useWalletClient();
  const scanClient = useScanClient();
  const { primaryPartyId } = useUserState();

  const [featured, setFeatured] = useState<boolean | undefined>(undefined);
  useEffect(() => {
    const getFeatured = async () => {
      if (primaryPartyId !== undefined) {
        const request = new LookupFeaturedAppRightRequest().setProviderPartyId(primaryPartyId);
        const value = await scanClient.lookupFeaturedAppRight(request);
        const featured = value.getFeaturedAppRight();
        setFeatured(featured !== undefined);
      }
    };
    getFeatured();
  }, [scanClient, primaryPartyId]);

  const selfFeature = () => {
    walletClient.selfGrantFeaturedAppRights();
  };

  if (!featured)
    return (
      <Button variant="contained" color="info" onClick={selfFeature} id="self-feature">
        {' '}
        Self-grant featured app rights
      </Button>
    );
  else return <div></div>;
};

export default SelfFeatureButton;
