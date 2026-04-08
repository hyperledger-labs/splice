// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as Yaml from 'js-yaml';
import { readFileSync } from 'fs';
import { mergeWith } from 'lodash';
import { dirname, join, resolve, sep as pathSeparator } from 'path';
import { env } from 'process';

import { spliceEnvConfig } from './envConfig';

export function readAndParseYaml(
  path: string,
  context: ConfigLoaderContext = initializeContext()
): unknown {
  const resolvedPath = resolveIncludedPath(path, context);
  if (resolvedPath in context.loadedFilesByPath) {
    return context.loadedFilesByPath[resolvedPath];
  } else if (context.pathStack.includes(resolvedPath)) {
    const cycle = [
      ...context.pathStack.slice(context.pathStack.lastIndexOf(resolvedPath)),
      resolvedPath,
    ];
    reportError(`Cyclic dependency detected: [${cycle.join(' -> ')}].`, context);
  } else {
    try {
      context.pathStack.push(resolvedPath);
      // TODO(#3231) The following breaks the config dumper from `make cluster/pulumi/test` but
      //             according to the design we want it.
      // console.log(`Loading configuration from [${resolvedPath}].`);
      const config = Yaml.load(readFileSync(resolvedPath, 'utf-8'), { schema: context.schema });
      context.loadedFilesByPath[resolvedPath] = config;
      return config;
    } catch (error) {
      if (error instanceof ConfigError) {
        throw error;
      }
      reportError(`${error}`, context);
    } finally {
      context.pathStack.pop();
    }
  }
}

function initializeContext(baseSchema: Yaml.Schema = Yaml.DEFAULT_SCHEMA): ConfigLoaderContext {
  const context: ConfigLoaderContext = {
    pathStack: [],
    loadedFilesByPath: {},
    schema: baseSchema,
  };
  context.schema = context.schema.extend([makeIncludeTagDefinition(context), appendTagDefinition]);
  return context;
}

type ConfigLoaderContext = {
  pathStack: Array<string>;
  loadedFilesByPath: Partial<Record<string, unknown>>;
  schema: Yaml.Schema;
};

function resolveIncludedPath(path: string, context: ConfigLoaderContext): string {
  const pathWithVariableSubstitutions = join(
    ...path.split(pathSeparator).map(segment => {
      if (segment === '') {
        return '/';
      } else if (segment.startsWith('$')) {
        const variableName = segment.slice(1);
        const variableValue = env[variableName];
        return variableValue === undefined || variableValue === ''
          ? reportError(
              `Included path [${path}] references variable [${variableName}] that is not defined.`,
              context
            )
          : variableValue;
      } else {
        return segment;
      }
    })
  );

  return context.pathStack.length === 0
    ? resolve(pathWithVariableSubstitutions) // resolve against CWD
    : resolve(
        dirname(context.pathStack[context.pathStack.length - 1]),
        pathWithVariableSubstitutions
      );
}

function makeIncludeTagDefinition(context: ConfigLoaderContext): Yaml.Type {
  return new Yaml.Type('!include', {
    kind: 'mapping',
    multi: true,
    construct(data, tag) {
      const { paths } = parseIncludeTag(tag as string, context); // tag cannot be undefined here
      const configs = paths.map(path => ({ value: readAndParseYaml(path, context) }));
      // All of the merged configs are wrapped in { value: } because the mergeStrategy does not
      // apply to the actual arguments of mergeWith, only nested properties.
      return mergeWith({}, ...configs, { value: data }, mergeStrategy).value;
    },
  });
}

function parseIncludeTag(tag: string, context: ConfigLoaderContext): ParsedIncludeTag {
  const paths =
    /^!include\((?<paths>[^;]+(?:;[^;]+)*)\)$/.exec(tag)?.groups?.paths?.split(';') ??
    reportError(`Include [${tag}] is malformed.`, context);
  return { paths };
}

type ParsedIncludeTag = {
  paths: Array<string>;
};

function mergeStrategy(included: unknown, overrides: unknown): unknown {
  // includes without overrides get included unchanged
  if (overrides === null) {
    return included;
  }
  if (
    Array.isArray(included) &&
    Array.isArray(overrides) &&
    '_append' in overrides &&
    overrides._append
  ) {
    return [...included, ...overrides];
  }
  // do not merge sequences index-wise
  if (Array.isArray(included) || Array.isArray(overrides)) {
    return overrides;
  }
  return undefined; // apply default merge strategy
}

const appendTagDefinition = new Yaml.Type('!append', {
  kind: 'sequence',
  construct(data) {
    Object.defineProperty(data, '_append', { enumerable: false, value: true });
    return data;
  },
});

function reportError(message: string, context: ConfigLoaderContext): never {
  const currentPath = context.pathStack[context.pathStack.length - 1];
  throw new ConfigError(currentPath, message);
}

class ConfigError extends Error {
  constructor(path: string, message: string) {
    super(`Config loading failed while loading [${path}]. ${message}`);
  }
}

export function loadClusterYamlConfig(): unknown {
  return readAndParseYaml(getClusterConfigPath());
}

export function getClusterConfigPath(): string {
  return join(spliceEnvConfig.context.clusterPath(), 'config.yaml');
}
