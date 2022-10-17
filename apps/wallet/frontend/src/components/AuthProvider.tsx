import { Auth0Provider } from '@auth0/auth0-react';
import React from 'react';

import { config } from '../utils';

const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  // TODO(i988) Remove hack where we don't use Auth0Provider if the website doesn't run on a secure origin
  if (crypto.subtle === undefined) {
    console.warn(
      'Could not find browser crypto implementation, are you running in a secure context?'
    );
    return <>{children}</>;
  }

  const authConfig = config.auth;

  if (authConfig.domain === '' || authConfig.clientId === '') {
    console.warn(
      "Required auth config fields 'domain' or 'clientId' are empty, modify '/config.js' to set them: ",
      authConfig
    );
    return <>{children}</>;
  }

  return <Auth0Provider {...config.auth}>{children}</Auth0Provider>;
};

export default AuthProvider;
