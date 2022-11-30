import { WebStorageStateStore } from 'oidc-client-ts';
import React from 'react';
import { AuthProvider as OidcAuthProvider } from 'react-oidc-context';

import { config, isHs2456UnsafeAuthConfig } from '../utils';

const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const authConfig = config.auth;

  if (isHs2456UnsafeAuthConfig(authConfig)) {
    return <>{children}</>;
  }

  if (authConfig.authority === '' || authConfig.client_id === '') {
    console.warn(
      "Required auth config fields 'authority' or 'client_id' are empty, modify '/config.js' to set them: ",
      authConfig
    );
    return <>{children}</>;
  }

  return (
    <OidcAuthProvider
      {...authConfig}
      onSigninCallback={() =>
        window.history.replaceState({}, document.title, window.location.pathname)
      }
      userStore={new WebStorageStateStore({ store: window.localStorage })}
    >
      {children}
    </OidcAuthProvider>
  );
};

export default AuthProvider;
