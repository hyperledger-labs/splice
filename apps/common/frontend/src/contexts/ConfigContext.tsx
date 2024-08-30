// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { Component, Context, useContext } from 'react';
import { z } from 'zod';

import { ConfigReader } from '../config';

export class ConfigProvider<
  A extends z.ZodRawShape,
  B extends z.ZodTypeAny,
  T extends z.ZodObject<A, 'strip', B>
> extends Component<{
  children: React.ReactNode;
  configScheme: T;
  configContext: Context<z.infer<T> | undefined>;
}> {
  render(): JSX.Element {
    const configReader = new ConfigReader(this.props.configScheme);
    const config = configReader.loadConfig();
    const ConfigContext = this.props.configContext;
    return <ConfigContext.Provider value={config}>{this.props.children}</ConfigContext.Provider>;
  }
}

export const useConfig: <T>(configContext: Context<T | undefined>) => T = configContext => {
  const config = useContext(configContext);
  if (!config) {
    throw new Error('config is not initialized');
  }
  return config;
};
