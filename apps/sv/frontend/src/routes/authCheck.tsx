import * as React from 'react';
import { Login, useInterval, useUserState } from 'common-frontend';
import { AuthConfig, TestAuthConfig } from 'common-frontend/lib/config/schema';
import { useCallback, useEffect, useState } from 'react';
import { Outlet } from 'react-router-dom';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const { isAuthenticated, userId, userAccessToken, primaryPartyId, updateStatus } = useUserState();
  const updateStatusWhenAuthenticated = useCallback(() => {
    if (isAuthenticated) {
      updateStatus({ userOnboarded: true, userWalletInstalled: false, partyId: primaryPartyId! });
    }
  }, [isAuthenticated, primaryPartyId, updateStatus]);

  useInterval(updateStatusWhenAuthenticated);

  const svClient = useSvAdminClient();
  const [isAuthorized, setIsAuthorized] = useState<boolean>(false);

  useEffect(() => {
    svClient
      .isAuthorized()
      .then(r => setIsAuthorized(true))
      .catch(error => {
        setIsAuthorized(false);
      });
  }, [svClient, userAccessToken, isAuthenticated]);

  if (!isAuthorized) {
    console.debug('undefined isAuthorized');
  }
  console.log(userId);
  console.log(typeof userId == 'undefined');
  console.log(isAuthorized && userId === undefined);
  if (isAuthenticated && isAuthorized) {
    return <Outlet />;
  } else {
    return (
      <Login
        loginFailed={!isAuthorized && userId !== undefined}
        title="SV Operations"
        authConfig={authConfig}
        testAuthConfig={testAuthConfig}
      />
    );
  }
};

export default AuthCheck;
