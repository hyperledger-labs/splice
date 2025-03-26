// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as openapi from 'splitwell-openapi';
import { OpenAPILoggingMiddleware } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React, { useContext, useMemo } from 'react';

const SplitwellContext = React.createContext<openapi.SplitwellApi | undefined>(undefined);

export interface SplitwellProps {
  url: string;
}

export const SplitwellClientProvider: React.FC<React.PropsWithChildren<SplitwellProps>> = ({
  url,
  children,
}) => {
  const client: openapi.SplitwellApi = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new openapi.ServerConfiguration(url, {}),
      promiseMiddleware: [new OpenAPILoggingMiddleware('splitwell')],
    });
    return new openapi.SplitwellApi(configuration);
  }, [url]);

  return <SplitwellContext.Provider value={client}>{children}</SplitwellContext.Provider>;
};

export const useSplitwellClient: () => openapi.SplitwellApi = () => {
  const client = useContext<openapi.SplitwellApi | undefined>(SplitwellContext);
  if (!client) {
    throw new Error('SplitwellClient not initialized');
  }
  return client;
};
