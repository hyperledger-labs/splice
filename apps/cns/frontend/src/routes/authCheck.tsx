import * as React from 'react';
import { AuthConfig, TestAuthConfig, Login, useUserState } from 'common-frontend';
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
    return (
      <Login title="Canton Name Service" authConfig={authConfig} testAuthConfig={testAuthConfig} />
    );
  }
};

export default AuthCheck;
