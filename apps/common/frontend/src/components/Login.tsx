import { useState } from 'react';

import { Button, Chip, Grid, TextField, Typography } from '@mui/material';

import { isHs256UnsafeAuthConfig } from '../config';
import { AuthConfig, TestAuthConfig } from '../config/schema';
import { useUserState } from '../contexts';

const Login: React.FC<{
  title: string;
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}> = ({ title, authConfig, testAuthConfig }) => {
  const mainLoginPrompt = isHs256UnsafeAuthConfig(authConfig) ? (
    <SstLoginPrompt
      secret={authConfig.secret}
      audience={authConfig.token_audience}
      scope={authConfig.token_scope}
    />
  ) : (
    <OidcLoginPrompt />
  );
  const testLoginPrompt = testAuthConfig ? (
    <>
      <Chip label="OR" sx={{ margin: '25px 0px' }} />
      <SstLoginPrompt
        title="Use Test Auth"
        secret={testAuthConfig.secret}
        audience={testAuthConfig.token_audience}
        scope={testAuthConfig.token_scope}
      />
    </>
  ) : undefined;
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
        {title}
      </Typography>

      {mainLoginPrompt}

      {testLoginPrompt}
    </Grid>
  );
};

function normalizeUserId(candidateId: string): string | undefined {
  // Return a normalized user ID, only if a valid user ID exists.
  const id = candidateId.trim();

  if (id.match(/^[a-zA-Z0-9@^$.!`\-#+'~_|:]{1,128}$/)) {
    return id;
  }
}

const SstLoginPrompt: React.FC<{
  title?: string;
  secret: string;
  audience: string;
  scope?: string;
}> = ({ title, secret, audience, scope }) => {
  const [userId, setUserId] = useState<string>('');
  const { loginWithSst } = useUserState();

  const invalidUserId = userId.length > 0 && !normalizeUserId(userId);

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
        error={invalidUserId}
        helperText={invalidUserId ? 'Invalid User Id' : undefined}
        onChange={uid => setUserId(uid.target.value)}
        onKeyPress={ev => {
          if (ev.key === 'Enter') {
            const id = normalizeUserId(userId);
            if (id) {
              loginWithSst(id, secret, audience, scope);
              ev.preventDefault();
            }
          }
        }}
      />
      <Button
        variant="contained"
        disabled={!normalizeUserId(userId)}
        sx={{ marginTop: '15px' }}
        onClick={e => {
          e.preventDefault();
          loginWithSst(normalizeUserId(userId) || '', secret, audience, scope);
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
