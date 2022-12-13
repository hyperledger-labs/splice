import { WebStorageStateStore } from 'oidc-client-ts';
import React from 'react';
import { AuthProvider as OidcAuthProvider } from 'react-oidc-context';

import { AuthConfig, isHs256UnsafeAuthConfig } from '../config/schema';
import { oidcAuthToProviderProps } from '../utils';

const AuthProvider: React.FC<{ children: React.ReactNode; authConf: AuthConfig }> = ({
  authConf,
  children,
}) => {
  if (isHs256UnsafeAuthConfig(authConf)) {
    return <>{children}</>;
  }

  return (
    <OidcAuthProvider
      {...oidcAuthToProviderProps(authConf)}
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
