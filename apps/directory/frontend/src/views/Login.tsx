import { isHs256UnsafeAuthConfig } from 'common-frontend';
import { useState } from 'react';

import { Button, Chip, Grid, TextField, Typography } from '@mui/material';

import { useUserState } from '../contexts/UserContext';
import { config } from '../utils/';

// TODO(#1445) Reduce duplication with other frontends; move common parts to common

const Login: React.FC = () => {
  const mainLoginPrompt = isHs256UnsafeAuthConfig(config.auth) ? (
    <SstLoginPrompt secret={config.auth.secret} />
  ) : (
    <OidcLoginPrompt />
  );
  const testLoginPrompt = config.testAuth ? (
    <>
      <Chip label="OR" sx={{ margin: '25px 0px' }} />
      <SstLoginPrompt title={'Use Test Auth'} secret={config.testAuth.secret} />
    </>
  ) : (
    ''
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
        Canton Name Service Log In
      </Typography>

      {mainLoginPrompt}

      {testLoginPrompt}
    </Grid>
  );
};

const SstLoginPrompt: React.FC<{ title?: string; secret: string }> = ({ title, secret }) => {
  const [userId, setUserId] = useState<string>('');
  const { loginWithSst } = useUserState();
  return (
    <>
      {title && (
        <Typography variant="h5" sx={{ marginBottom: '8px' }}>
          {title}
        </Typography>
      )}
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
          loginWithSst(userId, secret);
        }}
        id="login-button"
      >
        Log In
      </Button>
    </>
  );
};

const OidcLoginPrompt: React.FC = () => {
  const { loginWithOidc } = useUserState();
  return (
    <Button variant="outlined" onClick={loginWithOidc} id="oidc-login-button">
      Log in with OAuth2
    </Button>
  );
};

export default Login;
