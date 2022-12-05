import { SignJWT } from 'jose';
import jwtDecode, { JwtPayload } from 'jwt-decode';
import { User } from 'oidc-client-ts';
import React, { useContext, useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';

// TODO(#1445) Reduce duplication with wallet frontend; move common parts to common

interface UserState {
  // undefined when not logged in
  userId?: string;
  userAccessToken?: string;

  isAuthenticated: boolean;

  loginWithSst: (id: string, secret: string) => void;
  loginWithOidc: () => void;
  logout: () => void;
}

const UserContext = React.createContext<UserState | undefined>(undefined);

export const UserProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  // Two user authentication methods are supported:
  //   - sst: Self-Signed Tokens based on a given user ID
  //   - oidc: OpenID Connect logins based on OAuth2.0
  const [authMethod, setAuthMethod] = useState<'sst' | 'oidc' | undefined>(undefined);

  const [userId, setUserId] = useState<string>();
  const [userAccessToken, setUserAccessToken] = useState<string>();

  const auth = useAuth();

  const isAuthenticated =
    auth.isAuthenticated || (userId !== undefined && userAccessToken !== undefined);

  useEffect(() => {
    async function f(user: User) {
      setAuthMethod('oidc');

      const { access_token } = user;
      if (access_token) {
        setUserId(jwtDecode<JwtPayload>(access_token).sub);
        setUserAccessToken(access_token);
      } else {
        console.warn('WARNING: Expected an Access Token, but got nothing...');
      }
    }

    if (auth?.isAuthenticated && auth.user) {
      f(auth.user);
    }
  }, [auth]);

  return (
    <UserContext.Provider
      value={{
        userId,
        userAccessToken,
        isAuthenticated,
        loginWithSst: async (id: string, secret: string) => {
          setAuthMethod('sst');
          setUserId(id);

          const token = await generateLedgerApiToken(id, secret);
          setUserAccessToken(token);
        },
        loginWithOidc: () => {
          if (auth) {
            auth.signinRedirect();
          }
        },
        logout: () => {
          setUserId(undefined);
          setUserAccessToken(undefined);

          if (auth && authMethod === 'oidc') {
            auth.removeUser();
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

// Generate a local token for test purposes.
// Note that we are creating a ledger API token here; we're adding a scope so the ledger API is happy.
// TODO(#1445) reduce duplication with `common/.../LedgerApiContext.tsx`
const generateLedgerApiToken = async (userId: string, secret: string): Promise<string> => {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: { name: 'SHA-256' } },
    false,
    ['sign']
  );
  return new SignJWT({ scope: 'daml_ledger_api' }) // <- different to app backend tokens
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuedAt()
    .setSubject(userId)
    .sign(key);
};
