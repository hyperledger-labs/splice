// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { User } from 'oidc-client-ts';
import React, { useCallback, useContext, useEffect, useState } from 'react';
import { AuthState, useAuth } from 'react-oidc-context';

import {
  AuthConfig,
  getHs256UnsafeSecret,
  isHs256UnsafeAuthConfig,
  TestAuthConfig,
} from '../config/schema';
import { APP_MANAGER_LOCAL_STORAGE_KEY } from '../utils/AppManager';
import { generateToken, isHs256UnsafeToken, tryDecodeTokenSub } from '../utils/auth';

interface UserState {
  // undefined when not logged in
  userId?: string;
  userAccessToken?: string;

  isAuthenticated: boolean;
  oidcAuthState?: AuthState;

  loginWithSst: (id: string, secret: string, audience: string, scope?: string) => void;
  loginWithOidc: () => void;
  logout: () => void;
}

export const UserContext = React.createContext<UserState | undefined>(undefined);

// useAuth hook throws an error if used without a parent AuthProvider context,
// which is OK if the app supports only a hs-256-unsafe auth config
const useAuthSafe = () => {
  try {
    return useAuth();
  } catch {
    return undefined;
  }
};

const SESSION_STORAGE_KEY = 'canton.network.wallet.userid';

export const UserProvider: React.FC<{
  children: React.ReactNode;
  authConf: AuthConfig;
  testAuthConf?: TestAuthConfig;
  useLedgerApiTokens?: boolean;
}> = ({ children, authConf, testAuthConf }) => {
  // Two user authentication methods are supported:
  //   - sst: Self-Signed Tokens based on a given user ID
  //   - oidc: OpenID Connect logins based on OAuth2.0
  const authMethod: 'sst' | 'oidc' = isHs256UnsafeAuthConfig(authConf) ? 'sst' : 'oidc';

  const [userId, setUserId] = useState<string>();
  const [userAccessToken, setUserAccessToken] = useState<string>();

  const auth = useAuthSafe();

  const isAuthenticated =
    userId !== undefined &&
    userAccessToken !== undefined &&
    (auth?.isAuthenticated || isHs256UnsafeToken(userAccessToken));

  if (!isAuthenticated) {
    console.debug(
      `Not authenticated, userId: ${userId}, userAccessToken is set: ${
        userAccessToken !== undefined
      }, ` +
        `auth provider is authenticated: ${auth?.isAuthenticated}, isHs256unsafeToken: ${
          userAccessToken ? isHs256UnsafeToken(userAccessToken) : 'undefined'
        }`
    );
    const error = auth?.error;
    if (error) {
      console.warn(`oidc login error: ${error.message}`);
    }
  }

  const loginWithSst = useCallback(
    async (userId: string, secret: string, audience: string, scope?: string) => {
      setUserId(userId);

      const token = await generateToken(userId, secret, audience, scope);

      setUserAccessToken(token);
      window.sessionStorage.setItem(SESSION_STORAGE_KEY, userId);
    },
    []
  );

  const loginWithOidc = () => {
    if (auth) {
      // see AuthProvider.tsx's extractTargetFromUser
      const state = { redirectTo: window.location.href.replace(window.location.origin, '') };
      // We store the user id in localStorage. If it really was cleared
      // users should get a chance to login as a different user.
      auth.signinRedirect({ prompt: 'login', state });
    }
  };

  useEffect(() => {
    async function f(user: User) {
      const { access_token } = user;
      const access_token_sub = tryDecodeTokenSub(access_token);

      if (access_token_sub) {
        setUserId(access_token_sub);
        setUserAccessToken(access_token);
      } else {
        console.warn('WARNING: Got no usable token from auth provider.');
      }
    }

    if (auth?.isAuthenticated && auth.user) {
      f(auth.user);
    } else if (authMethod === 'sst') {
      const storedUserId = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
      const secret = getHs256UnsafeSecret(authConf);
      if (storedUserId) {
        loginWithSst(storedUserId, secret, authConf.token_audience, authConf.token_scope);
      }
    } else if (testAuthConf) {
      const storedUserId = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
      const secret = testAuthConf.secret;
      if (storedUserId) {
        loginWithSst(storedUserId, secret, authConf.token_audience, authConf.token_scope);
      }
    }
  }, [auth, authConf, authMethod, loginWithSst, testAuthConf]);

  return (
    <UserContext.Provider
      value={{
        oidcAuthState: auth,
        isAuthenticated,
        userId,
        userAccessToken,
        loginWithSst,
        loginWithOidc,
        logout: () => {
          console.debug('Logout initiated');
          setUserId(undefined);
          setUserAccessToken(undefined);

          if (auth && authMethod === 'oidc') {
            auth.removeUser();
          }
          if (authMethod === 'sst' || testAuthConf) {
            window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
          }
          window.localStorage.removeItem(APP_MANAGER_LOCAL_STORAGE_KEY);
          console.debug('Logout completed');
        },
      }}
    >
      {children}
    </UserContext.Provider>
  );
};

export const useUserState: () => UserState = () => {
  const user = useContext<UserState | undefined>(UserContext);
  if (!user) {
    throw new Error('User context not initialized');
  }
  console.debug(`user state: userId: ${user.userId}, isAuthenticated: ${user.isAuthenticated}`);
  return user;
};
