import * as React from 'react';
import { Login, useInterval, useUserState } from 'common-frontend';
import { AuthConfig, TestAuthConfig } from 'common-frontend/lib/config/schema';
import { useCallback } from 'react';
import { Outlet } from 'react-router-dom';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const { isAuthenticated, userId, primaryPartyId, updateStatus } = useUserState();
  const isAuthenticatedSv1 = isAuthenticated && userId! === 'sv1';
  const updateStatusWhenAuthenticated = useCallback(() => {
    if (isAuthenticatedSv1) {
      updateStatus({ userOnboarded: true, userWalletInstalled: false, partyId: primaryPartyId! });
    }
  }, [isAuthenticatedSv1, primaryPartyId, updateStatus]);

  useInterval(updateStatusWhenAuthenticated);

  if (isAuthenticatedSv1) {
    return <Outlet />;
  } else {
    return <Login title="SV Operations" authConfig={authConfig} testAuthConfig={testAuthConfig} />;
  }
};

export default AuthCheck;
