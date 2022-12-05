import { isHs256UnsafeAuthConfig } from 'common-frontend';
import { SignJWT } from 'jose';
import { User } from 'oidc-client-ts';
import React, { useCallback, useContext, useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';

import { config } from '../utils/config';
import { UserStatusResponse } from './WalletServiceContext';

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

  loginWithSst: (id: string) => void;
  loginWithOidc: () => void;
  logout: () => void;
}

const UserContext = React.createContext<UserState | undefined>(undefined);

// useAuth hook throws an error if used without a parent AuthProvider context,
// which is actually OK & expected if the app is running with a hs-256-unsafe auth config
const useAuthSafe = () => {
  try {
    return useAuth();
  } catch {
    return undefined;
  }
};

const SESSION_STORAGE_KEY = 'canton.network.wallet.userid';

export const UserProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  // Two user authentication methods are supported:
  //   - sst: Self-Signed Tokens based on a given user ID
  //   - oidc: OpenID Connect logins based on OAuth2.0
  const authMethod: 'sst' | 'oidc' = isHs256UnsafeAuthConfig(config.auth) ? 'sst' : 'oidc';

  const [isOnboarded, setIsOnboarded] = useState(false);
  const [userId, setUserId] = useState<string>();
  const [primaryPartyId, setPrimaryPartyId] = useState<string>();
  const [userAccessToken, setUserAccessToken] = useState<string>();

  const auth = useAuthSafe();

  const isAuthenticated = auth
    ? auth.isAuthenticated
    : userId !== undefined && userAccessToken !== undefined;

  const loginWithSst = useCallback(async (userId: string) => {
    setUserId(userId);
    const token = await generateToken(userId);
    setUserAccessToken(token);
    window.sessionStorage.setItem(SESSION_STORAGE_KEY, userId);
  }, []);

  useEffect(() => {
    async function f(user: User) {
      setUserId(user.profile?.sub);

      const { id_token } = user;
      if (!id_token) {
        console.warn('WARNING: Expected an ID Token, but got nothing...');
      }
      setUserAccessToken(id_token);
    }

    if (auth?.isAuthenticated && auth.user) {
      f(auth.user);
    } else if (authMethod === 'sst') {
      const storedUserId = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
      if (storedUserId) {
        loginWithSst(storedUserId);
      }
    }
  }, [auth, authMethod, loginWithSst]);

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
        loginWithSst: loginWithSst,
        loginWithOidc: () => {
          if (auth) {
            // We store the user id in localStorage. If it really was cleared
            // users should get a chance to login as a different user.
            auth.signinRedirect({ prompt: 'login' });
          }
        },
        logout: () => {
          setUserId(undefined);
          setPrimaryPartyId(undefined);
          setUserAccessToken(undefined);
          setIsOnboarded(false);

          if (auth && authMethod === 'oidc') {
            auth.removeUser();
          } else if (authMethod === 'sst') {
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

// Generate a local token for test purposes. Only acceptable by the
// wallet service if it is running in unsafe mode
const generateToken = async (userId: string): Promise<string> => {
  if (isHs256UnsafeAuthConfig(config.auth)) {
    const secret = new TextEncoder().encode(config.auth.secret);
    const key = await crypto.subtle.importKey(
      'raw',
      secret,
      { name: 'HMAC', hash: { name: 'SHA-256' } },
      false,
      ['sign']
    );

    return new SignJWT({})
      .setProtectedHeader({ alg: 'HS256' })
      .setIssuedAt()
      .setSubject(userId)
      .sign(key);
  } else {
    throw new Error('Invalid auth configuration, check /config.js');
  }
};
