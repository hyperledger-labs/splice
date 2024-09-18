import * as React from 'react';
import { Login, useUserState, AuthConfig, TestAuthConfig } from 'common-frontend';
import { Outlet } from 'react-router-dom';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const { isAuthenticated } = useUserState();

  if (!isAuthenticated) {
    return <Login title="App Manager" authConfig={authConfig} testAuthConfig={testAuthConfig} />;
  }

  return <Outlet />;
};

export default AuthCheck;
