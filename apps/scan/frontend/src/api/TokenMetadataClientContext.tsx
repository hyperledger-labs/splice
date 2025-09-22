// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as openapi from '@lfdecentralizedtrust/token-metadata-openapi';
import { OpenAPILoggingMiddleware } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React, { useContext, useMemo } from 'react';

const TokenMetadataClientContext = React.createContext<openapi.DefaultApi | undefined>(undefined);

// Scan serves TokenMetadata
export interface ScanConfigProps {
  url: string;
}

export const TokenMetadataClientProvider: React.FC<React.PropsWithChildren<ScanConfigProps>> = ({
  url,
  children,
}) => {
  const client: openapi.DefaultApi | undefined = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new openapi.ServerConfiguration(url, {}),
      promiseMiddleware: [new OpenAPILoggingMiddleware('TokenMetadata')],
    });

    return new openapi.DefaultApi(configuration);
  }, [url]);

  return (
    <TokenMetadataClientContext.Provider value={client}>
      {children}
    </TokenMetadataClientContext.Provider>
  );
};

export const useTokenMetadataClient: () => openapi.DefaultApi = () => {
  const client = useContext<openapi.DefaultApi | undefined>(TokenMetadataClientContext);
  if (!client) {
    throw new Error('TokenMetadata client not initialized');
  }
  return client;
};
