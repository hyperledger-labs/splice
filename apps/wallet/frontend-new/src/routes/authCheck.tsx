import * as React from 'react';
import { useUserState } from 'common-frontend';
import { AuthConfig, TestAuthConfig } from 'common-frontend/lib/config/schema';
import { OnboardedStatus } from 'common-frontend/lib/contexts/UserContext';
import { useEffect } from 'react';
import { Outlet } from 'react-router-dom';

import Loading from '../components/Loading';
import Onboarding from '../components/Onboarding';
import { useWalletClient } from '../contexts/WalletServiceContext';
import Login from './login';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const { isAuthenticated, onboardedStatus, updateStatus } = useUserState();
  const walletService = useWalletClient();
  useEffect(() => {
    if (onboardedStatus === OnboardedStatus.Loading) {
      walletService.userStatus().then(status => updateStatus(status));
    }
  });

  if (isAuthenticated) {
    if (onboardedStatus === OnboardedStatus.Onboarded) {
      return <Outlet />;
    } else if (onboardedStatus === OnboardedStatus.NotOnboarded) {
      return <Onboarding />;
    } else {
      return <Loading />;
    }
  } else {
    return <Login authConfig={authConfig} testAuthConfig={testAuthConfig} />;
  }
};

export default AuthCheck;
