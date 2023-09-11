import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { test, expect } from 'vitest';

import App from '../App';

test('login shows alice party ID', async () => {
  // arrange
  const user = userEvent.setup();
  render(<App />);

  // act
  const input = screen.getByRole('textbox');
  await user.type(input, 'alice');

  const button = screen.getByRole('button');
  await user.click(button);

  // assert
  expect(() =>
    screen.findByDisplayValue(
      'alice::122015ba7aa9054dbad217110e8fbf5dd550a59fb56df5986913f7b9a8e63bad8570'
    )
  ).toBeDefined();
});
