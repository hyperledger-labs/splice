import { render, screen } from '@testing-library/react';
import { test, expect } from 'vitest';

import App from '../App';
import { AppManagerConfigProvider } from '../utils/config';

test('login screen shows up', async () => {
  // arrange
  render(
    <AppManagerConfigProvider>
      <App />
    </AppManagerConfigProvider>
  );

  // assert
  expect(() => screen.findByText('Log In')).toBeDefined();
});
