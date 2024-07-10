// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
type Arr = readonly unknown[];

const MAX_OBJ_LENGTH_TO_LOG = 300;
export function callWithLogging<T extends Arr, R>(
  callerName: string,
  operationName: string,
  call: (...args: T) => Promise<R>,
  ...args: T
): Promise<R> {
  const traceId = Math.random().toString(36).slice(-8);
  const callName = `${operationName}(${args
    .filter(arg => typeof arg !== 'undefined') // turns out that JSON.stringify returns `undefined` instead of a string if arg is undefined
    .map(arg => JSON.stringify(arg).substring(0, MAX_OBJ_LENGTH_TO_LOG))
    .join(', ')}) [${traceId}]`;

  console.debug(`${callerName} calling ${callName}`);
  return call(...args)
    .catch(reason => {
      console.debug(`${callerName} call ${callName} rejected with ${JSON.stringify(reason)}`);
      throw reason;
    })
    .then(value => {
      console.debug(
        `${callerName} call ${callName} completed with `,
        value && JSON.stringify(value).substring(0, MAX_OBJ_LENGTH_TO_LOG)
      );
      return value;
    });
}
