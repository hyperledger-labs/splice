// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Auth0Config } from './auth0types';

// TODO(#2873): can we get to a point where we no longer even export this outside of this file?
export const DEFAULT_AUDIENCE = 'https://canton.network.global';

export function getSvAppApiAudience(auth0Config: Auth0Config): string {
  return auth0Config.appToApiAudience['sv'] || DEFAULT_AUDIENCE;
}

export function getValidatorAppApiAudience(auth0Config: Auth0Config): string {
  return auth0Config.appToApiAudience['validator'] || DEFAULT_AUDIENCE;
}

export function getLedgerApiAudience(auth0Config: Auth0Config): string {
  // TODO(#2873): consider renaming 'participant' to 'ledger-api', but that would be a more breaking change
  return auth0Config.appToApiAudience['participant'] || DEFAULT_AUDIENCE;
}
