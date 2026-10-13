// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, test, vi } from 'vitest';

import QueryDevtools from '../components/QueryDevtools';

vi.mock('@tanstack/react-query-devtools', () => ({
  ReactQueryDevtools: () => <div data-testid="react-query-devtools" />,
}));

describe('QueryDevtools', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  test('renders the react-query devtools by default', () => {
    render(<QueryDevtools />);
    expect(screen.getByTestId('react-query-devtools')).toBeDefined();
  });

  test('renders nothing when VITE_DISABLE_QUERY_DEVTOOLS is true', () => {
    vi.stubEnv('VITE_DISABLE_QUERY_DEVTOOLS', 'true');
    render(<QueryDevtools />);
    expect(screen.queryByTestId('react-query-devtools')).toBeNull();
  });
});
