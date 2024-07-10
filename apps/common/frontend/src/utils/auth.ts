// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { SignJWT, decodeJwt, decodeProtectedHeader } from 'jose';
import { AuthProviderProps } from 'react-oidc-context';

import { AuthConfig, isHs256UnsafeAuthConfig } from '../config/schema';

export const oidcAuthToProviderProps = (config: AuthConfig): AuthProviderProps => {
  if (!isHs256UnsafeAuthConfig(config)) {
    const { token_audience, token_scope, ...props } = config;

    // We include the `offline_access` scope to tell auth0 we want refresh tokens when we first authenticate.
    // The refresh tokens are then used to automatically retrieve new access tokens before they expire without re-authenticating.
    const scope = [token_scope, 'offline_access'].filter(s => !!s).join(' ');

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
