import { OnboardUserRequest } from 'common-protobuf/com/daml/network/validator/v0/validator_service_pb';
import { useState } from 'react';

import { Button, Grid, Typography } from '@mui/material';

import { useUserState } from '../contexts/UserContext';
import { useValidatorClient } from '../contexts/ValidatorServiceContext';

const Onboarding: React.FC<{ onOnboard: () => Promise<void> }> = ({ onOnboard }) => {
  const validatorClient = useValidatorClient();
  const { userId } = useUserState();
  const [onboardClicked, setOnboardClicked] = useState<boolean>(false);

  if (!userId) {
    return <div>Loading...</div>;
  }

  const onOnboardUser = async () => {
    setOnboardClicked(true);
    await validatorClient.onboardUser(new OnboardUserRequest().setName(userId), undefined);
    await onOnboard();
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
        variant="contained"
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
