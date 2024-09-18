// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useSearchParams } from 'react-router-dom';
import { z } from 'zod';

import { Algorithm, authSchema } from '..';

// TODO(#8369) Reconsider how we pass this and what exactly is specified here.
const appManagerSchema = z.object({
  oidcAuthority: z.string().url(),
  jsonApi: z.string().url(),
  wallet: z.string().url(),
  // We use the client id here to pass the provider-party-id along since
  // that makes the integration in the third-party app
  // a bit easier as the full config can be statically
  // determined without needing to make a request
  // to its backend to query for the provider id.
  clientId: z.string(),
});

export type AppManagerConfig = z.infer<typeof appManagerSchema>;

export const APP_MANAGER_LOCAL_STORAGE_KEY = 'canton.network.appmanager.config';
export const APP_MANAGER_QUERY_KEY = 'appmanager_config';

export const useAppManagerConfig = (): AppManagerConfig | undefined => {
  const [searchParams] = useSearchParams();
  const config =
    searchParams.get(APP_MANAGER_QUERY_KEY) ||
    window.localStorage.getItem(APP_MANAGER_LOCAL_STORAGE_KEY);
  if (config) {
    window.localStorage.setItem(APP_MANAGER_LOCAL_STORAGE_KEY, config);
    const parsedConfig = appManagerSchema.parse(JSON.parse(config));
    return parsedConfig;
  }
  return undefined;
};

// TODO(#8369) Stop hardcoding half of the parameters here
export const appManagerAuthConfig = (
  clientId: string,
  oidcAuthority: string
): z.infer<typeof authSchema> => ({
  algorithm: Algorithm.RS256,
  token_scope: 'daml_ledger_api',
  token_audience: 'https://canton.network.global',
  authority: oidcAuthority,
  client_id: clientId,
});

export const appLaunchUrl = (config: AppManagerConfig, url: string): string => {
  const result = new URL(url);
  result.searchParams.set(APP_MANAGER_QUERY_KEY, JSON.stringify(config));
  return result.toString();
};
