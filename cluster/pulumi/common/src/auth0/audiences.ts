// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Auth0Config } from "./auth0types";

export const DEFAULT_AUDIENCE = 'https://canton.network.global'; // FIXME: can we get to a point where we no longer even export this outside of this file?

export function getSvAppApiAudience(
  auth0Config: Auth0Config,
): string {
  return auth0Config.appToApiAudience['sv'] || DEFAULT_AUDIENCE
}

export function getValidatorAppApiAudience(
  auth0Config: Auth0Config,
): string {
  return auth0Config.appToApiAudience['validator'] || DEFAULT_AUDIENCE
}

export function getParticipantApiAudience(
  auth0Config: Auth0Config,
): string {
  return auth0Config.appToApiAudience['participant'] || DEFAULT_AUDIENCE
}
