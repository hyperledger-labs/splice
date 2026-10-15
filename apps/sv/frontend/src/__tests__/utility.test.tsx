// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { test, expect, describe } from 'vitest';

import { formatBasisPoints } from '../utils/governance';

describe('Utility', () => {
  test('formatBasisPoints function works as expected', () => {
    expect(formatBasisPoints('0')).toBe('0_0000');
    expect(formatBasisPoints('1')).toBe('0_0001');
    expect(formatBasisPoints('1010')).toBe('0_1010');
    expect(formatBasisPoints('12345')).toBe('1_2345');
  });
});
