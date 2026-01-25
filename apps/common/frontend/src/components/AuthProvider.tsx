// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Log, User, WebStorageStateStore } from 'oidc-client-ts';
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

  // Note: we tag the log output of the oidc-client-ts library to make it easier
  // to handle in lnav (:filter-out) and ignore patterns for the log checker script (canton_log.ignore.txt).
  const debugLogger = {
    debug: (...args: unknown[]) => console.debug('[oidc-client-ts] ', ...args),
    info: (...args: unknown[]) => console.info('[oidc-client-ts] ', ...args),
    warn: (...args: unknown[]) => console.warn('[oidc-client-ts] ', ...args),
    error: (...args: unknown[]) => console.error('[oidc-client-ts] ', ...args),
  };
  Log.setLogger(debugLogger);
  Log.setLevel(Log.DEBUG);

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
