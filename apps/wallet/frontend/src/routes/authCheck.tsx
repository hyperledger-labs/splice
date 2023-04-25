import * as React from 'react';
import { Login, useUserState, useInterval } from 'common-frontend';
import Loading from 'common-frontend/lib/components/Loading';
import { AuthConfig, TestAuthConfig } from 'common-frontend/lib/config/schema';
import { OnboardedStatus } from 'common-frontend/lib/contexts/UserContext';
import { useCallback } from 'react';
import { Outlet } from 'react-router-dom';

import Onboarding from '../components/Onboarding';
import { useWalletClient } from '../contexts/WalletServiceContext';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const { isAuthenticated, onboardedStatus, updateStatus } = useUserState();
  const { userStatus } = useWalletClient();
  const updateStatusWhenAuthenticated = useCallback(() => {
    if (isAuthenticated) {
      userStatus().then(status => updateStatus(status));
    }
  }, [isAuthenticated, userStatus, updateStatus]);

  useInterval(updateStatusWhenAuthenticated);

  if (isAuthenticated) {
    if (onboardedStatus === OnboardedStatus.Onboarded) {
      return <Outlet />;
    } else if (onboardedStatus === OnboardedStatus.NotOnboarded) {
      return <Onboarding />;
    } else {
      return <Loading />;
    }
  } else {
    return <Login title="Canton Wallet" authConfig={authConfig} testAuthConfig={testAuthConfig} />;
  }
};

export default AuthCheck;
