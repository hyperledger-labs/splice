import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { test, expect, describe } from 'vitest';

import App from '../App';
import { svPartyId } from './mocks/constants';

describe('SV user can', () => {
  test('login and see the SV party ID', async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    const button = screen.getByRole('button', { name: 'Log In' });
    user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });

  test('browse to the governance tab', async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
  });
});
