import { useUserState } from 'common-frontend';
import { useState } from 'react';

import { Button, Grid, Typography } from '@mui/material';

import { useValidatorClient } from '../contexts/ValidatorServiceContext';
import { useWalletClient } from '../contexts/WalletServiceContext';

const Onboarding: React.FC = () => {
  const validatorService = useValidatorClient();
  const walletService = useWalletClient();
  const { userId, updateStatus } = useUserState();
  const [onboardClicked, setOnboardClicked] = useState<boolean>(false);

  if (!userId) {
    return <div>Loading...</div>;
  }

  const onOnboardUser = async () => {
    setOnboardClicked(true);
    await validatorService.registerUser();
    let status = await walletService.userStatus();
    // it may take a while for the wallet to realize the user is onboarded, so we retry
    while (!status.userOnboarded) {
      await new Promise(resolve => setTimeout(resolve, 1000));
      status = await walletService.userStatus();
    }
    updateStatus(status);
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
        Welcome to the Canton Network wallet application!
      </Typography>
      <Typography variant="h6" sx={{ marginBottom: '15px' }}>
        Your Daml user name is '{userId}'.
      </Typography>
      <Typography variant="body1">
        You are not onboarded onto this participant, or your wallet installation is missing. Press
        the button below to start using the wallet.
      </Typography>

      <Button
        variant="pill"
        sx={{ margin: '15px' }}
        onClick={e => {
          e.preventDefault();
          onOnboardUser();
        }}
        disabled={onboardClicked}
        id="onboard-button"
      >
        Onboard yourself
      </Button>

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
