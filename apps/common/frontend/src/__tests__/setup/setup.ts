// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { cleanup } from '@testing-library/react';
import { beforeEach, afterEach } from 'vitest';

beforeEach(() => {
  // @ts-expect-error Fixes "cannot serialize bigint" from react-query
  BigInt.prototype['toJSON'] = function () {
    return this.toString();
  };
});

afterEach(() => {
  cleanup();
});
