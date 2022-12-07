import { SignJWT, decodeJwt, decodeProtectedHeader } from 'jose';
import { AuthProviderProps } from 'react-oidc-context';

export const extendWithLedgerApiClaims = (props: AuthProviderProps): AuthProviderProps => {
  // We need this for our auth0 test tenant setup so auth0 gives us the correct access token.
  // Note that we don't request the openid scope here, which means we'll only get the access token.
  // This is mainly a workaround for the fact that Canton can't parse JTWs with multiple audiences
  // (auth0 adds the "../userinfo" audience if we request the openid scope).
  const scope = 'daml_ledger_api';
  // TODO(#1836) Pick a future-proof audience that doesn't depend on a modified canton instance.
  const extraQueryParams = { audience: 'https://canton.network.global' };

  return { scope, extraQueryParams, ...props };
};

// Generate a local token for test purposes. Only acceptable by the
// wallet service if it is running in unsafe mode
export const generateToken = async (
  userId: string,
  secret: string,
  scope?: string
): Promise<string> => {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: { name: 'SHA-256' } },
    false,
    ['sign']
  );
  return new SignJWT({ scope })
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuedAt()
    .setSubject(userId)
    .sign(key);
};

// Generate a local token for test purposes that is accepted by the ledger API.
export const generateLedgerApiToken = async (userId: string, secret: string): Promise<string> => {
  return generateToken(userId, secret, 'daml_ledger_api');
};

export const isHs256UnsafeToken = (token: string): boolean => {
  return decodeProtectedHeader(token).alg === 'HS256';
};

export const tryDecodeTokenSub = (token?: string): string | undefined => {
  try {
    return decodeJwt(token!).sub;
  } catch (_) {}
};
