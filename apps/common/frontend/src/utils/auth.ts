import { SignJWT, decodeJwt, decodeProtectedHeader } from 'jose';
import { AuthProviderProps } from 'react-oidc-context';

import { AuthConfig, isHs256UnsafeAuthConfig } from '../config/schema';

export const oidcAuthToProviderProps = (config: AuthConfig): AuthProviderProps => {
  if (!isHs256UnsafeAuthConfig(config)) {
    const { token_audience, token_scope, ...props } = config;
    const scope = token_scope;
    const extraQueryParams = { audience: token_audience };
    const redirect_uri = window.location.origin;

    return { scope, extraQueryParams, redirect_uri, ...props };
  } else {
    throw new Error(
      'oidcAuthToProviderProps should only be called with rs-256 based auth configs.'
    );
  }
};

// Generate a local token for test purposes. Only acceptable by the
// wallet service if it is running in unsafe mode
export const generateToken = async (
  userId: string,
  secret: string,
  audience: string,
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
    .setAudience(audience)
    .setSubject(userId)
    .sign(key);
};

export const isHs256UnsafeToken = (token: string): boolean => {
  return decodeProtectedHeader(token).alg === 'HS256';
};

export const tryDecodeTokenSub = (token?: string): string | undefined => {
  try {
    return decodeJwt(token!).sub;
  } catch (_) {}
};
