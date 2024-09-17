// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export interface DarFileMissingError {
  errors: string[];
  warnings: {
    unknownTemplateIds: string[];
  };
}

function hasProp<T extends string>(x: unknown, key: T): x is { [key in T]: unknown } {
  return typeof x === 'object' && x !== null && key in x;
}

export function isDarFileMissingError(error: unknown): error is DarFileMissingError {
  return (
    hasProp(error, 'errors') &&
    Array.isArray(error.errors) &&
    error.errors.includes('Cannot resolve any template ID from request') &&
    hasProp(error, 'warnings') &&
    hasProp(error.warnings, 'unknownTemplateIds') &&
    Array.isArray(error.warnings.unknownTemplateIds)
  );
}
