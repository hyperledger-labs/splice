import { RedirectLoginOptions, useAuth0 } from '@auth0/auth0-react';
import { SignJWT } from 'jose';
import React, { useContext, useEffect, useState } from 'react';

import { UserStatusResponse } from './WalletServiceContext';

interface UserState {
  // undefined when not logged in
  userId?: string;
  userAccessToken?: string;

  isOnboarded: boolean;
  primaryPartyId?: string; // undefined when not onboarded

  // It makes to sense to track user onboarding status & party info in the user store,
  // but to avoid circular dependencies between the UserContext and the WalletServiceContext
  // (which needs a userId or userAccessToken to authenticate requests to the `userStatus` gRPC endpoint)
  // we expose an external callback to update the User store's internal state after login happens
  updateStatus: (status: UserStatusResponse) => void;

  loginWithId: (id: string) => void;
  loginWithAuth0: (options?: RedirectLoginOptions) => void;
  logout: () => void;
}

const UserContext = React.createContext<UserState | undefined>(undefined);

export const UserProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [authMethod, setAuthMethod] = useState<'id' | 'auth0' | undefined>(undefined);
  const [isOnboarded, setIsOnboarded] = useState(false);
  const [userId, setUserId] = useState<string>();
  const [primaryPartyId, setPrimaryPartyId] = useState<string>();
  const [userAccessToken, setUserAccessToken] = useState<string>();

  const auth0 = useAuth0();

  useEffect(() => {
    async function f() {
      setAuthMethod('auth0');
      setUserId(auth0.user?.sub);

      const { id_token } = await auth0.getAccessTokenSilently({ detailedResponse: true });
      setUserAccessToken(id_token);
    }

    if (auth0.isAuthenticated) {
      f();
    }
  }, [auth0]);

  return (
    <UserContext.Provider
      value={{
        isOnboarded,
        userId,
        userAccessToken,
        primaryPartyId,
        updateStatus: ({ userOnboarded, partyId }) => {
          setIsOnboarded(userOnboarded);
          setPrimaryPartyId(partyId);
        },
        loginWithId: async (id: string) => {
          setAuthMethod('id');
          setUserId(id);

          // TODO(i988) Drop if-guard after enabling TLS in cluster
          if (crypto.subtle === undefined) {
            console.warn('Could not find browser crypto implementation, not generating user token');
          } else {
            const token = await generateToken(id);
            setUserAccessToken(token);
          }
        },
        loginWithAuth0: auth0.loginWithRedirect,
        logout: () => {
          setUserId(undefined);
          setPrimaryPartyId(undefined);
          setUserAccessToken(undefined);
          setIsOnboarded(false);

          if (authMethod === 'auth0') {
            auth0.logout();
          }
          setAuthMethod(undefined);
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
  const secret = new TextEncoder().encode('test');
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
};
