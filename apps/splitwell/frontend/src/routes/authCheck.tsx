import * as React from 'react';
import { Login, useUserState } from 'common-frontend';
import { AuthConfig, TestAuthConfig } from 'common-frontend/lib/config/schema';
import { Outlet } from 'react-router-dom';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const { isAuthenticated } = useUserState();

  if (isAuthenticated) {
    return <Outlet />;
  } else {
    return <Login title="Splitwell" authConfig={authConfig} testAuthConfig={testAuthConfig} />;
  }
};

export default AuthCheck;
