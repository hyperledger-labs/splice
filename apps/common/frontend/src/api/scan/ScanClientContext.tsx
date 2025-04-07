// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as openapi from 'scan-openapi';
import { OpenAPILoggingMiddleware } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React, { useContext, useMemo } from 'react';

const ScanClientContext = React.createContext<openapi.ScanApi | undefined>(undefined);

export interface ScanProps {
  url: string;
}

export const ScanClientProvider: React.FC<React.PropsWithChildren<ScanProps>> = ({
  url,
  children,
}) => {
  const client: openapi.ScanApi | undefined = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new openapi.ServerConfiguration(url, {}),
      promiseMiddleware: [new OpenAPILoggingMiddleware('scan')],
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
