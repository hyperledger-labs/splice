import { Button, Grid, Typography } from '@mui/material';

import { OnboardUserRequest } from '../com/daml/network/validator/v0/validator_service_pb';
import { useValidatorClient } from '../contexts/ValidatorServiceContext';

const Onboarding: React.FC<{ userId: string }> = ({ userId }) => {
  const validatorClient = useValidatorClient();

  const onOnboardUser = async () => {
    await validatorClient.onboardUser(new OnboardUserRequest().setName(userId), null);
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
