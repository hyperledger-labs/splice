import { render, screen } from '@testing-library/react';
import { test, expect } from 'vitest';

import App from '../App';

test('login screen shows up', async () => {
  // arrange
  render(<App />);

  // assert
  expect(() => screen.findByText('Log In')).toBeDefined();
});
