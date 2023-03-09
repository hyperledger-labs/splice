import { useScanClient } from 'common-frontend';
import { useUserState } from 'common-frontend';
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
        const featured = await scanClient.lookupFeaturedAppRight(primaryPartyId);
        setFeatured(!!featured); // HTTP api might return null
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
