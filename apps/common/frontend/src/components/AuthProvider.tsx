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
      automaticSilentRenew
      userStore={new WebStorageStateStore({ store: window.localStorage })}
      onSigninCallback={() =>
        window.history.replaceState({}, document.title, window.location.pathname)
      }
      {...oidcAuthToProviderProps(authConf)}
    >
      {children}
    </OidcAuthProvider>
  );
};

export default AuthProvider;
