import { Auth0Provider } from '@auth0/auth0-react';
import React from 'react';

import { config, isHs2456UnsafeAuthConfig } from '../utils';

const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const authConfig = config.auth;

  if (isHs2456UnsafeAuthConfig(authConfig)) {
    return <>{children}</>;
  }

  if (authConfig.domain === '' || authConfig.clientId === '') {
    console.warn(
      "Required auth config fields 'domain' or 'clientId' are empty, modify '/config.js' to set them: ",
      authConfig
    );
    return <>{children}</>;
  }

  return <Auth0Provider {...authConfig}>{children}</Auth0Provider>;
};

export default AuthProvider;
