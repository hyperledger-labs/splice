// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { getNamespaceConfig } from './auth0';
import { Auth0Config } from './auth0types';

// TODO(#2873): can we get to a point where we no longer even export this outside of this file?
export const DEFAULT_AUDIENCE = 'https://canton.network.global';

export function getSvAppApiAudience(auth0Config: Auth0Config, namespace: string): string {
  return getNamespaceConfig(auth0Config, namespace).audiences.svAppApi!;
}

export function getValidatorAppApiAudience(auth0Config: Auth0Config, namespace: string): string {
  return getNamespaceConfig(auth0Config, namespace).audiences.validatorApi;
}

export function getLedgerApiAudience(auth0Config: Auth0Config, namespace: string): string {
  return getNamespaceConfig(auth0Config, namespace).audiences.ledgerApi;
}
