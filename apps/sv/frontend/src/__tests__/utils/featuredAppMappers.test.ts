// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, it, expect } from 'vitest';
import {
  appKindSelectOptions,
  appKindToOptional,
  diffOpsParties,
  opsPartiesToOptional,
} from '../../utils/governance';

describe('appKindToOptional', () => {
  it('maps blank to null (DAML default)', () => {
    expect(appKindToOptional('')).toBeNull();
  });

  it('passes through a concrete kind', () => {
    expect(appKindToOptional('FAK_AssetMoving')).toBe('FAK_AssetMoving');
  });
});

describe('opsPartiesToOptional', () => {
  const alice = 'alice::1220bbbb';
  const bob = 'bob::1220cccc';

  it('maps empty to null', () => {
    expect(opsPartiesToOptional([])).toBeNull();
  });

  it('maps all-blank rows to null (canonical empty → None)', () => {
    expect(opsPartiesToOptional(['', '  '])).toBeNull();
  });

  it('trims and drops blank rows', () => {
    expect(opsPartiesToOptional([`  ${alice}  `, '', bob])).toEqual([alice, bob]);
  });
});

describe('diffOpsParties', () => {
  const alice = 'alice::1220bbbb';
  const bob = 'bob::1220cccc';
  const carol = 'carol::1220dddd';

  it('reports no change for identical lists', () => {
    expect(diffOpsParties([alice, bob], [alice, bob])).toEqual({ added: [], removed: [] });
  });

  it('reports added and removed', () => {
    expect(diffOpsParties([alice, bob], [alice, carol])).toEqual({
      added: [carol],
      removed: [bob],
    });
  });

  it('treats None (empty base) as all-added', () => {
    expect(diffOpsParties([], [alice])).toEqual({ added: [alice], removed: [] });
  });
});

describe('appKindSelectOptions', () => {
  it('leads with a blank default option then the kinds', () => {
    const opts = appKindSelectOptions();
    expect(opts[0]).toEqual({ key: 'Default (General)', value: '' });
    expect(opts.map(o => o.value)).toContain('FAK_General');
    expect(opts.map(o => o.value)).toContain('FAK_AssetMoving');
  });
});
