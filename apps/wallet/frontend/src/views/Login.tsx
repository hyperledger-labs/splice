import { useState } from 'react';

import { Button, Chip, Grid, TextField, Typography } from '@mui/material';

import { useUserState } from '../contexts/UserContext';

const Login: React.FC = () => {
  const [userId, setUserId] = useState<string>('');
  const { loginWithId, loginWithAuth0 } = useUserState();

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
          loginWithId(userId);
        }}
        id="login-button"
      >
        Log In
      </Button>

      <Chip label="OR" sx={{ margin: '25px 0px' }} />

      <Button variant="outlined" onClick={loginWithAuth0}>
        Log in with auth0
      </Button>
    </Grid>
  );
};

export default Login;
