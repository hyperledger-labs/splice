import { User } from 'oidc-client-ts';
import React, { useCallback, useContext, useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';

import {
  AuthConfig,
  getHs256UnsafeSecret,
  isHs256UnsafeAuthConfig,
  TestAuthConfig,
} from '../config/schema';
import {
  generateLedgerApiToken,
  generateToken,
  isHs256UnsafeToken,
  tryDecodeTokenSub,
} from '../utils/auth';

interface UserState {
  // undefined when not logged in
  userId?: string;
  userAccessToken?: string;

  isAuthenticated: boolean;
  isOnboarded: boolean;
  primaryPartyId?: string; // undefined when not onboarded

  // It makes to sense to track user onboarding status & party info in the user store,
  // but to avoid circular dependencies between the UserContext and the WalletServiceContext
  // (which needs a userId or userAccessToken to authenticate requests to the `userStatus` gRPC endpoint)
  // we expose an external callback to update the User store's internal state after login happens
  updateStatus: (status: UserStatusResponse) => void;

  loginWithSst: (id: string, secret: string) => void;
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
}> = ({ children, authConf, testAuthConf, useLedgerApiTokens }) => {
  // Two user authentication methods are supported:
  //   - sst: Self-Signed Tokens based on a given user ID
  //   - oidc: OpenID Connect logins based on OAuth2.0
  const authMethod: 'sst' | 'oidc' = isHs256UnsafeAuthConfig(authConf) ? 'sst' : 'oidc';

  const [isOnboarded, setIsOnboarded] = useState(false);
  const [userId, setUserId] = useState<string>();
  const [primaryPartyId, setPrimaryPartyId] = useState<string>();
  const [userAccessToken, setUserAccessToken] = useState<string>();

  const auth = useAuthSafe();

  const isAuthenticated =
    userId !== undefined &&
    userAccessToken !== undefined &&
    (auth?.isAuthenticated || isHs256UnsafeToken(userAccessToken));

  const loginWithSst = useCallback(
    async (userId: string, secret: string) => {
      setUserId(userId);
      const token = await (useLedgerApiTokens
        ? generateLedgerApiToken(userId, secret)
        : generateToken(userId, secret));
      setUserAccessToken(token);
      window.sessionStorage.setItem(SESSION_STORAGE_KEY, userId);
    },
    [useLedgerApiTokens]
  );

  const loginWithOidc = () => {
    if (auth) {
      // We store the user id in localStorage. If it really was cleared
      // users should get a chance to login as a different user.
      auth.signinRedirect({ prompt: 'login' });
    }
  };

  useEffect(() => {
    async function f(user: User) {
      const { access_token, id_token } = user;

      // If we did't request a specific audience or scope, auth0 gives us an
      // access token that is opaque, i.e., it has an invalid jwt payload and
      // we can't use it for auth against our backends. We must use the ID
      // token then, which we hopefully receive and which hopefully has a valid
      // jwt payload. Auth is great!
      const access_token_sub = tryDecodeTokenSub(access_token);
      const id_token_sub = tryDecodeTokenSub(id_token);

      if (access_token_sub) {
        setUserId(access_token_sub);
        setUserAccessToken(access_token);
      } else if (id_token_sub) {
        setUserId(id_token_sub);
        setUserAccessToken(id_token);
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
        loginWithSst(storedUserId, secret);
      }
    } else if (testAuthConf) {
      const storedUserId = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
      const secret = testAuthConf.secret;
      if (storedUserId) {
        loginWithSst(storedUserId, secret);
      }
    }
  }, [auth, authConf, authMethod, loginWithSst, testAuthConf]);

  return (
    <UserContext.Provider
      value={{
        isAuthenticated,
        isOnboarded,
        userId,
        userAccessToken,
        primaryPartyId,
        updateStatus: ({ userOnboarded, partyId }) => {
          setIsOnboarded(userOnboarded);
          setPrimaryPartyId(partyId);
        },
        loginWithSst,
        loginWithOidc,
        logout: () => {
          setUserId(undefined);
          setPrimaryPartyId(undefined);
          setUserAccessToken(undefined);
          setIsOnboarded(false);

          if (auth && authMethod === 'oidc') {
            auth.removeUser();
          }
          if (authMethod === 'sst' || testAuthConf) {
            window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
          }
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
  return user;
};

export interface UserStatusResponse {
  userOnboarded: boolean;
  partyId: string;
}
