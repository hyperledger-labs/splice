// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

// Configuration specified in files that are not part of this build.
// To use this external configuration, add a script file to the web site that is loaded
// before the application code, and writes the config to the global "window" variable.
const externalConfig = window.splice_config;

// The configuration can also be specified in an environment variable,
// where the value of the variable is the config object serialized as a JSON string.
// The environment variable is evaluated at build time and takes precedence over
// the external config.
const envConfigString = import.meta.env.VITE_SPLICE_CONFIG;

export class ConfigReader<
  A extends z.ZodRawShape,
  B extends z.ZodTypeAny,
  T extends z.ZodObject<A, 'strip', B>,
> {
  private schema;

  constructor(schema: T) {
    this.schema = schema;
  }

  loadConfig(): z.infer<T> {
    const parseConfig = (config: unknown) => {
      const parsedConfig = this.schema.safeParse(config);
      if (parsedConfig.success) {
        return parsedConfig.data;
      } else {
        throw new Error(
          `Error when parsing config: ${JSON.stringify(parsedConfig.error, null, 2)}.`
        );
      }
    };

    if (envConfigString !== undefined) {
      const envConfig = JSON.parse(envConfigString);
      // Printing whole config files to the log is usually a bad idea because it can leak secrets,
      // but frontend configs are inherently unsafe and must not contain any secrets.
      console.info(`Config from VITE_SPLICE_CONFIG:`, envConfigString);
      window.splice_config = envConfig;
      return parseConfig(envConfig);
    } else if (externalConfig !== undefined) {
      console.info(`Config from window.splice_config:`, externalConfig);
      return parseConfig(externalConfig);
    } else {
      throw new Error(
        `No configuration found. Make sure 'window.splice_config' is set before the UI code is loaded.`
      );
    }
  }
}
