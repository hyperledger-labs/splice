// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material';
import { describe, expect, test } from 'vitest';

import NetworkBanner from '../components/NetworkBanner';
import { theme } from '../theme';

const renderBanner = (networkName: string) =>
  render(
    <ThemeProvider theme={theme}>
      <NetworkBanner networkName={networkName} />
    </ThemeProvider>
  );

describe('NetworkBanner', () => {
  test('shows the current non-mainnet network', () => {
    renderBanner('ScratchNet');

    expect(screen.getByText('You are on ScratchNet')).toBeDefined();
  });

  test('is hidden on mainnet', () => {
    renderBanner('MainNet');

    expect(screen.queryByTestId('network-instance-name')).toBeNull();
  });
});
