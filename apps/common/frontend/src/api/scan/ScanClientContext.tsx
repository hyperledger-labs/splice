// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as openapi from '@lfdecentralizedtrust/scan-openapi';
import { OpenAPILoggingMiddleware } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React, { useContext, useMemo } from 'react';

const ScanClientContext = React.createContext<openapi.ScanApi | undefined>(undefined);

export interface ScanProps {
  baseScanUrl: string;
}

export const ScanClientProvider: React.FC<React.PropsWithChildren<ScanProps>> = ({
  baseScanUrl,
  children,
}) => {
  // safety check
  const url = baseScanUrl.endsWith('/api/scan') ? baseScanUrl : `${baseScanUrl}/api/scan`;
  const client: openapi.ScanApi | undefined = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new openapi.ServerConfiguration(url, {}),
      promiseMiddleware: [
        new OpenAPILoggingMiddleware('scan'),
        {
          pre: async context => {
            context.setHeaderParam('x-source-ui', 'scan');
            return context;
          },
          post: async context => context,
        },
      ],
    });

    return new openapi.ScanApi(configuration);
  }, [url]);

  return <ScanClientContext.Provider value={client}>{children}</ScanClientContext.Provider>;
};

export const useScanClient: () => openapi.ScanApi = () => {
  const client = useContext<openapi.ScanApi | undefined>(ScanClientContext);
  if (!client) {
    throw new Error('Scan client not initialized');
  }
  return client;
};
