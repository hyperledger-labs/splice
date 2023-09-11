import { render, screen } from '@testing-library/react';
import { test, expect } from 'vitest';

import App from '../App';

test('home screen shows up', async () => {
  // arrange
  render(<App />);

  // assert
  expect(() => screen.findByText('Canton Coin Scan')).toBeDefined();
});
