import * as React from 'react';
import {
  ErrorDisplay,
  Loading,
  Login,
  useUserState,
  OnboardedStatus,
  AuthConfig,
  TestAuthConfig,
} from 'common-frontend';
import { Outlet } from 'react-router-dom';

import Onboarding from '../components/Onboarding';
import { useUserStatus } from '../hooks';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const { isAuthenticated, onboardedStatus } = useUserState();
  const userStatusQuery = useUserStatus();

  if (isAuthenticated) {
    if (userStatusQuery.isLoading) {
      return <Loading />;
    } else if (userStatusQuery.isError) {
      return <ErrorDisplay message={'Error while fetching user status'} />;
    } else {
      if (onboardedStatus === OnboardedStatus.Onboarded) {
        return <Outlet />;
      } else if (onboardedStatus === OnboardedStatus.NotOnboarded) {
        return <Onboarding />;
      } else {
        return <Loading />;
      }
    }
  } else {
    return <Login title="Canton Wallet" authConfig={authConfig} testAuthConfig={testAuthConfig} />;
  }
};

export default AuthCheck;
