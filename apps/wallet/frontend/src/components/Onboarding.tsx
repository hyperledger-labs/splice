// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DisableConditionally, Loading, useUserState } from 'common-frontend';
import { useState } from 'react';

import { Button, Grid, Typography } from '@mui/material';

import { useValidatorClient } from '../contexts/ValidatorServiceContext';
import { useWalletConfig } from '../utils/config';

const Onboarding: React.FC = () => {
  const config = useWalletConfig();
  const validatorService = useValidatorClient();
  const { userId } = useUserState();

  const [onboardClicked, setOnboardClicked] = useState<boolean>(false);

  if (!userId) {
    return <Loading />;
  }

  const onOnboardUser = async () => {
    setOnboardClicked(true);
    await validatorService.registerUser();
  };

  return (
    <Grid
      height="100%"
      container
      spacing={0}
      direction="column"
      alignItems="center"
      justifyContent="center"
    >
      <Typography variant="h4" sx={{ marginBottom: '15px' }}>
        Welcome to the {config.spliceInstanceNames.networkName} wallet application!
      </Typography>
      <Typography variant="h6" sx={{ marginBottom: '15px' }}>
        Your Daml user name is '{userId}'.
      </Typography>
      <Typography variant="body1">
        You are not onboarded onto this participant, or your wallet installation is missing. Press
        the button below to start using the wallet.
      </Typography>

      <DisableConditionally conditions={[{ disabled: onboardClicked, reason: 'Loading...' }]}>
        <Button
          variant="pill"
          sx={{ margin: '15px' }}
          onClick={e => {
            e.preventDefault();
            onOnboardUser();
          }}
          id="onboard-button"
        >
          Onboard yourself
        </Button>
      </DisableConditionally>

      <Typography variant="body2">
        Note: In the future, this functionality may move to a dedicated validator application.
      </Typography>
      <Typography variant="body2">
        You may also onboard yourself by calling <code>onboardUser()</code> on the validator app
        API.
      </Typography>
    </Grid>
  );
};

export default Onboarding;
