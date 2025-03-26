// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useUserState } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  BaseApiMiddleware,
  OpenAPILoggingMiddleware,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React, { useContext, useMemo } from 'react';
import {
  ScanProxyApi,
  ServerConfiguration,
  createConfiguration,
  Middleware,
  RequestContext,
  ResponseContext,
} from 'scan-proxy-openapi';

const ValidatorScanProxyContext = React.createContext<ScanProxyApi | undefined>(undefined);

export interface ValidatorScanProxyProps {
  validatorUrl: string;
}

class ApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

export const ValidatorScanProxyClientProvider: React.FC<
  React.PropsWithChildren<ValidatorScanProxyProps>
> = ({ validatorUrl, children }) => {
  const { userAccessToken } = useUserState();

  const client: ScanProxyApi | undefined = useMemo(() => {
    const configuration = createConfiguration({
      baseServer: new ServerConfiguration(validatorUrl, {}),
      promiseMiddleware: [
        new ApiMiddleware(userAccessToken),
        new OpenAPILoggingMiddleware('scan-proxy'),
      ],
    });

    return new ScanProxyApi(configuration);
  }, [validatorUrl, userAccessToken]);

  return (
    <ValidatorScanProxyContext.Provider value={client}>
      {children}
    </ValidatorScanProxyContext.Provider>
  );
};

export const useValidatorScanProxyClient: () => ScanProxyApi = () => {
  const client = useContext<ScanProxyApi | undefined>(ValidatorScanProxyContext);
  if (!client) {
    throw new Error('Validator scan proxy client not initialized');
  }
  return client;
};
