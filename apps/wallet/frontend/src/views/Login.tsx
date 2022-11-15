import { useState } from 'react';

import { Button, Grid, TextField, Typography } from '@mui/material';

import { useUserState } from '../contexts/UserContext';
import { config, isHs2456UnsafeAuthConfig } from '../utils';

const Login: React.FC = () => {
  const [userId, setUserId] = useState<string>('');
  const { loginWithSst, loginWithOidc } = useUserState();

  const loginMethod = isHs2456UnsafeAuthConfig(config.auth) ? (
    <>
      <TextField
        label="Daml User ID"
        required
        id="user-id-field"
        value={userId}
        onChange={uid => setUserId(uid.target.value)}
      ></TextField>
      <Button
        variant="contained"
        sx={{ marginTop: '15px' }}
        onClick={e => {
          e.preventDefault();
          loginWithSst(userId);
        }}
        id="login-button"
      >
        Log In
      </Button>
    </>
  ) : (
    <Button variant="outlined" onClick={loginWithOidc}>
      Log in with OAuth2
    </Button>
  );

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
        Wallet Log In
      </Typography>

      {loginMethod}
    </Grid>
  );
};

export default Login;
