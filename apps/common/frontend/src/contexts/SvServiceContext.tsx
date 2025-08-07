// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as openapi from '@lfdecentralizedtrust/sv-openapi';
import React, { useContext, useMemo } from 'react';
import { GetDsoInfoResponse, ServerConfiguration } from '@lfdecentralizedtrust/sv-openapi';

const SvContext = React.createContext<SvClient | undefined>(undefined);

export interface SVProps {
  url: string;
}

export interface SvClient {
  getDsoInfo: () => Promise<GetDsoInfoResponse>;
}

export const SvClientProvider: React.FC<React.PropsWithChildren<SVProps>> = ({ url, children }) => {
  const friendlyClient: SvClient | undefined = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
    });
    const svClient = new openapi.SvApi(configuration);

    return {
      getDsoInfo: async (): Promise<GetDsoInfoResponse> => {
        return await svClient.getDsoInfo();
      },
    };
  }, [url]);

  return <SvContext.Provider value={friendlyClient}>{children}</SvContext.Provider>;
};

export const useSvClient: () => SvClient = () => {
  const client = useContext<SvClient | undefined>(SvContext);
  if (!client) {
    throw new Error('SV client not initialized');
  }
  return client;
};
