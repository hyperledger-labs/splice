import { useState } from 'react';
import { useAuth } from 'react-oidc-context';

import { Button, Chip, Grid, TextField, Typography } from '@mui/material';

// useAuth hook throws an error if used without a parent AuthProvider context,
// which is actually OK & expected if the app is running with a hs-256-unsafe auth config
const useAuthSafe = () => {
  try {
    return useAuth();
  } catch {
    return undefined;
  }
};

const Login: React.FC<{ onLogin: (userId: string) => void }> = ({ onLogin }) => {
  const [userId, setUserId] = useState<string>('');
  const { signinRedirect } = useAuthSafe() || {};

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
        Canton Name Service Log In
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
          onLogin(userId);
        }}
        id="login-button"
      >
        Log In
      </Button>

      <Chip label="OR" sx={{ margin: '25px 0px' }} />

      <Button variant="outlined" onClick={() => signinRedirect && signinRedirect()}>
        Log in with OAuth2
      </Button>
    </Grid>
  );
};

export default Login;
