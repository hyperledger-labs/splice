import { RedirectLoginOptions, useAuth0 } from '@auth0/auth0-react';
import React, { useContext, useEffect, useState } from 'react';

import { UserStatusResponse } from './WalletServiceContext';

interface UserState {
  userId?: string; // undefined when not logged in
  userAccessToken?: string; // undefined when not logged in via auth0

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
        loginWithId: (id: string) => {
          setAuthMethod('id');
          setUserId(id);
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
