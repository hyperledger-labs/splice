import * as React from 'react';
import {
  ErrorDisplay,
  Loading,
  Login,
  useUserState,
  AuthConfig,
  TestAuthConfig,
} from 'common-frontend';
import { Outlet } from 'react-router-dom';

import Onboarding from '../components/Onboarding';
import { useIsOnboarded } from '../hooks';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const { isAuthenticated } = useUserState();
  const { isLoading, isError, data: isOnboarded } = useIsOnboarded();

  if (!isAuthenticated) {
    return <Login title="Canton Wallet" authConfig={authConfig} testAuthConfig={testAuthConfig} />;
  }

  if (isLoading) {
    return <Loading />;
  }

  if (isError) {
    return <ErrorDisplay message={'Error while fetching user status'} />;
  }

  if (isOnboarded) {
    return <Outlet />;
  } else {
    return <Onboarding />;
  }
};

export default AuthCheck;
