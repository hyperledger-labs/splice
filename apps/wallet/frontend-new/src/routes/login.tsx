import * as React from 'react';
import { isHs256UnsafeAuthConfig, theme, useUserState } from 'common-frontend';
import { AuthConfig, TestAuthConfig } from 'common-frontend/lib/config/schema';
import { useState } from 'react';
import { Navigate } from 'react-router-dom';

import { Person } from '@mui/icons-material';
import { Button, Divider, InputAdornment, OutlinedInput, Stack, Typography } from '@mui/material';
import Container from '@mui/material/Container';

interface LoginProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const Login: React.FC<LoginProps> = ({ authConfig, testAuthConfig }) => {
  const { isAuthenticated } = useUserState();
  if (isAuthenticated) {
    return <Navigate to="/" />;
  }

  // We have some integration tests that do Auth0 login tests and others that do self-signed token login tests,
  // but because we start the UIs outside sbt they have one static config.
  // So for tests the UI must support both algorithms at the same time;
  // for validator operator users, the algorithm choice should be mutually exclusive.
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
      <Divider flexItem>OR</Divider>
      <SstLoginPrompt
        secret={testAuthConfig.secret}
        audience={testAuthConfig.token_audience}
        scope={testAuthConfig.token_scope}
      />
    </>
  ) : undefined;

  return (
    <Container maxWidth="xs">
      <Stack alignItems="center" paddingTop={16} spacing={4}>
        <Typography variant="h5">CANTON WALLET</Typography>

        {mainLoginPrompt}

        {testLoginPrompt}
      </Stack>
    </Container>
  );
};

/**
 * OIDC is for OpenIDConnect. This is what Auth0 (and providers like Google) use.
 */
const OidcLoginPrompt: React.FC = () => {
  const { loginWithOidc } = useUserState();
  return (
    <Button id="oidc-login-button" variant="pill" fullWidth size="large" onClick={loginWithOidc}>
      Log In with OAuth2
    </Button>
  );
};

interface SstLoginPromptProps {
  secret: string;
  audience: string;
  scope?: string;
}
/**
 * SST is our abbreviation for "Self-Signed Token".
 * These are JWT tokens that are signed with some symmetric secret with HS256.
 */
const SstLoginPrompt: React.FC<SstLoginPromptProps> = ({ secret, audience, scope }) => {
  const [userId, setUserId] = useState<string>('');
  const { loginWithSst } = useUserState();
  const normalizedUserId = normalizeUserId(userId);
  const invalidUserId = userId.length > 0 && !normalizedUserId;
  return (
    <>
      <OutlinedInput
        id="user-id-field"
        value={userId}
        error={invalidUserId}
        onChange={evt => setUserId(evt.target.value)}
        onKeyDown={evt => {
          if (evt.key === 'Enter') {
            if (normalizedUserId) {
              loginWithSst(normalizedUserId, secret, audience, scope);
              evt.preventDefault();
            }
          }
        }}
        fullWidth
        placeholder="Username for test login"
        startAdornment={
          // https://stackoverflow.com/questions/69554151/how-to-change-material-ui-textfield-inputadornment-background-color
          <InputAdornment
            sx={{
              padding: theme.spacing(3.5, 2.5),
              backgroundColor: theme.palette.colors.neutral[20],
              borderRadius: '9999px 0 0 9999px',
            }}
            position="start"
          >
            <Person />
          </InputAdornment>
        }
        sx={{ borderRadius: '9999px', paddingLeft: 0 }}
      />
      <Button
        id="login-button"
        variant="pill"
        fullWidth
        size="large"
        disabled={!normalizedUserId}
        onClick={e => {
          e.preventDefault();
          loginWithSst(normalizedUserId!, secret, audience, scope);
        }}
      >
        Log In
      </Button>
    </>
  );
};

/**
 * @returns A normalized user ID, only if candidateId is a valid user ID.
 */
function normalizeUserId(candidateId: string): string | undefined {
  const id = candidateId.trim();

  if (id.match(/^[a-zA-Z0-9@^$.!`\-#+'~_|:]{1,128}$/)) {
    return id;
  }
}

export default Login;
