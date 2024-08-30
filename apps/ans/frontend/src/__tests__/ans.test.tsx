// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { test, expect } from 'vitest';

import App from '../App';
import { AnsConfigProvider } from '../utils';

test('login screen shows up', async () => {
  // arrange
  render(
    <AnsConfigProvider>
      <App />
    </AnsConfigProvider>
  );

  // assert
  expect(() => screen.findByText('Log In')).toBeDefined();
});
