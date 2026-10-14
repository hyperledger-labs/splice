// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { onAuthExpired } from '@canton-network/splice-common-frontend-utils';
import { afterEach, beforeEach, describe, expect, test, vi, type Mock } from 'vitest';

import { LedgerApiClient, PackageIdResolver } from '../../contexts';

class StubResolver extends PackageIdResolver {
  async resolveTemplateId(templateId: string): Promise<string> {
    return templateId;
  }
}

const okUserResponse = () =>
  new Response(JSON.stringify({ user: { primaryParty: 'alice::1220' } }), { status: 200 });

const errorResponse = (status: number) =>
  new Response(JSON.stringify({ error: 'nope' }), { status });

const makeClient = () => new LedgerApiClient('http://test/', 'tok', 'alice', new StubResolver());

const cleanups: Array<() => void> = [];
const subscribe = (handler: () => void) => {
  cleanups.push(onAuthExpired(handler));
};

let fetchMock: Mock<typeof fetch>;

beforeEach(() => {
  fetchMock = vi.fn<typeof fetch>();
  global.fetch = fetchMock;
});

afterEach(() => {
  for (const cleanup of cleanups) cleanup();
  cleanups.length = 0;
  vi.restoreAllMocks();
});

describe('LedgerApiClient.getPrimaryParty', () => {
  test('returns the primary party and does not fire the event on 200', async () => {
    const handler = vi.fn();
    subscribe(handler);
    fetchMock.mockResolvedValueOnce(okUserResponse());

    await expect(makeClient().getPrimaryParty()).resolves.toBe('alice::1220');
    expect(handler).not.toHaveBeenCalled();
  });

  test('fires auth-expired event on 401', async () => {
    const handler = vi.fn();
    subscribe(handler);
    fetchMock.mockResolvedValueOnce(errorResponse(401));

    await expect(makeClient().getPrimaryParty()).rejects.toThrow(/HTTP 401/);
    expect(handler).toHaveBeenCalledOnce();
  });

  test.each([400, 403, 404, 500])('does NOT fire the event on status %i', async status => {
    const handler = vi.fn();
    subscribe(handler);
    fetchMock.mockResolvedValueOnce(errorResponse(status));

    await expect(makeClient().getPrimaryParty()).rejects.toThrow();
    expect(handler).not.toHaveBeenCalled();
  });
});
