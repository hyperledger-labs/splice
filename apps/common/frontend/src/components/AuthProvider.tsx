import { User, WebStorageStateStore } from 'oidc-client-ts';
import React from 'react';
import { AuthProvider as OidcAuthProvider } from 'react-oidc-context';

import { AuthConfig, isHs256UnsafeAuthConfig } from '../config/schema';
import { oidcAuthToProviderProps } from '../utils';

interface AuthProviderProps {
  children: React.ReactNode;
  authConf: AuthConfig;
  redirect?: (path: string) => void;
}

const DEFAULT_REDIRECT = (path: string) => {
  window.history.replaceState({}, document.title, path);
};
const AuthProvider: React.FC<AuthProviderProps> = ({
  authConf,
  children,
  redirect = DEFAULT_REDIRECT,
}) => {
  if (isHs256UnsafeAuthConfig(authConf)) {
    return <>{children}</>;
  }

  return (
    <OidcAuthProvider
      automaticSilentRenew
      userStore={new WebStorageStateStore({ store: window.localStorage })}
      onSigninCallback={user => {
        const redirectTo = extractRedirectToFromUser(user);
        if (redirectTo) {
          redirect(redirectTo);
        } else {
          console.warn(
            'User in oidc signin callback is missing state/target. Using fallback redirect.',
            user
          );
          redirect('/');
        }
      }}
      {...oidcAuthToProviderProps(authConf)}
    >
      {children}
    </OidcAuthProvider>
  );
};

// See UserContext's loginWithOidc
function extractRedirectToFromUser(u: User | void): string | undefined {
  if (typeof u === 'object' && typeof u.state === 'object' && u.state) {
    return (u.state as { redirectTo?: string }).redirectTo;
  } else {
    return undefined;
  }
}

export default AuthProvider;
