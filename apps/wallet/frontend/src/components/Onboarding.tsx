// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  DisableConditionally,
  Loading,
  useUserState,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';

import { Button, Grid, Typography } from '@mui/material';

import { useValidatorClient } from '../contexts/ValidatorServiceContext';
import { useWalletConfig } from '../utils/config';
import { BasicLayout } from './Layout';

const Onboarding: React.FC = () => {
  const config = useWalletConfig();
  const { registerUser } = useValidatorClient();
  const { userId } = useUserState();
  const navigate = useNavigate();

  const onboardUserMutation = useMutation({
    mutationFn: async () => {
      return await registerUser();
    },
    onSuccess: () => {
      navigate('/transactions');
    },
    onError: error => {
      // TODO (DACH-NY/canton-network-node#5491): show an error to the user.
      console.error(`Failed to onboard user`, error);
    },
    retry: 3,
  });

  if (!userId) {
    return <Loading />;
  }

  return (
    <BasicLayout>
      <Grid
        height="100%"
        container
        spacing={0}
        direction="column"
        alignItems="center"
        justifyContent="center"
      >
        <Typography
          variant="h4"
          sx={{ marginBottom: '15px' }}
          data-testid={'wallet-onboarding-welcome-title'}
        >
          Welcome to the {config.spliceInstanceNames.networkName} wallet application!
        </Typography>
        <Typography variant="h6" sx={{ marginBottom: '15px' }}>
          Your Daml user name is '{userId}'.
        </Typography>
        <Typography variant="body1">
          You are not onboarded onto this participant, or your wallet installation is missing. Press
          the button below to start using the wallet.
        </Typography>

        <DisableConditionally
          conditions={[{ disabled: onboardUserMutation.isPending, reason: 'Loading...' }]}
        >
          <Button
            variant="pill"
            sx={{ margin: '15px' }}
            onClick={e => {
              e.preventDefault();
              onboardUserMutation.mutate();
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
    </BasicLayout>
  );
};

export default Onboarding;
