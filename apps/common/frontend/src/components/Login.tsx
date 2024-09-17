// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { useState } from 'react';

import { Person } from '@mui/icons-material';
import { Button, Divider, InputAdornment, OutlinedInput, Stack, Typography } from '@mui/material';
import Container from '@mui/material/Container';

import { isHs256UnsafeAuthConfig } from '../config';
import { AuthConfig, TestAuthConfig } from '../config/schema';
import { useUserState } from '../contexts';
import { theme } from '../theme';
import LoginFailed from './LoginFailed';
import { DisableConditionally } from './index';

interface LoginProps {
  title: string;
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
  loginFailed?: boolean;
  failureMessage?: string;
}

const Login: React.FC<LoginProps> = ({
  title,
  authConfig,
  testAuthConfig,
  loginFailed,
  failureMessage,
}) => {
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
      {loginFailed && <LoginFailed message={failureMessage} />}
      <Stack alignItems="center" paddingTop={16} spacing={4}>
        <Typography
          variant="h5"
          textTransform="uppercase"
          fontFamily={theme.fonts.monospace.fontFamily}
          fontWeight={theme.fonts.monospace.fontWeight}
        >
          {title}
        </Typography>
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
  const isValidUserId = validateUserId(userId);

  const { loginWithSst } = useUserState();

  return (
    <>
      <OutlinedInput
        id="user-id-field"
        value={userId}
        error={!isValidUserId}
        onChange={evt => setUserId(evt.target.value)}
        onKeyDown={evt => {
          if (evt.key === 'Enter') {
            loginWithSst(userId, secret, audience, scope);
            evt.preventDefault();
          }
        }}
        fullWidth
        placeholder="Username for test login"
        startAdornment={
          // https://stackoverflow.com/questions/69554151/how-to-change-material-ui-textfield-inputadornment-background-color
          <InputAdornment
            sx={{
              padding: theme.spacing(3.5, 2.5),
              backgroundColor: theme.palette.colors.neutral[10],
              borderRadius: '9999px 0 0 9999px',
            }}
            position="start"
          >
            <Person htmlColor="white" />
          </InputAdornment>
        }
        sx={{ borderRadius: '9999px', paddingLeft: 0 }}
      />
      <DisableConditionally
        conditions={[{ disabled: !isValidUserId, reason: `Invalid user ID '${userId}'` }]}
      >
        <Button
          id="login-button"
          variant="pill"
          fullWidth
          size="large"
          onClick={e => {
            e.preventDefault();
            loginWithSst(userId, secret, audience, scope);
            setUserId('');
          }}
        >
          Log In
        </Button>
      </DisableConditionally>
    </>
  );
};

/**
 * @returns True if candidateId is a valid user ID, False otherwise.
 */
function validateUserId(candidateId: string): boolean {
  return candidateId.length > 0 && !!candidateId.match(/^[a-zA-Z0-9@^$.!`\-#+'~_|:]{1,128}$/);
}

export default Login;
