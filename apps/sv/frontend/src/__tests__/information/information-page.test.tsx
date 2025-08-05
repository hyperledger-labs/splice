// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';

import { dsoInfo } from '@lfdecentralizedtrust/splice-common-test-handlers';
import App from '../../App';
import { config } from '../setup/config';
import { SvConfigProvider } from '../../utils';
import { svPartyId } from '../mocks/constants';
import userEvent from '@testing-library/user-event';

const AppWithConfig = () => {
  return (
    <SvConfigProvider>
      <App />
    </SvConfigProvider>
  );
};

describe('The Information page', () => {
  test('login and see the SV party ID', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);
    expect(await screen.findByText('Log In')).toBeDefined();
    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');
    const button = screen.getByRole('button', { name: 'Log In' });
    user.click(button);
    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });

  test('has 3 information tabs', async () => {
    render(<AppWithConfig />);
    expect(await screen.findByText('DSO Info')).toBeDefined();
    expect(await screen.findByText(`${config.spliceInstanceNames.amuletName} Info`)).toBeDefined();
    expect(await screen.findByText('CometBFT Debug Info')).toBeDefined();
  });

  test('has a General section with SVs information', async () => {
    render(<AppWithConfig />);
    expect(await screen.findByText('General')).toBeDefined();
    const valueCells = document.querySelectorAll('.general-dso-value-name');
    expect(valueCells).toHaveLength(9);
    const svUser = screen.getAllByText(dsoInfo.sv_user);
    expect(svUser).toHaveLength(1);
    const svParty = Array.from(valueCells.values()).filter(
      e => e.getAttribute('data-selenium-text') == dsoInfo.sv_party_id
    );
    expect(svParty).toHaveLength(3);
  });

  test('has a Domain Node Status section', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);
    expect(await screen.findByText('Domain Node Status')).toBeDefined();
    const domainNodeStatus = screen.getByRole('tab', { name: 'Domain Node Status' });
    await user.click(domainNodeStatus);
    expect(await screen.findByText('Sequencer status'));
    expect(await screen.findByText('Mediator status'));
    expect(await screen.findAllByText('active')).toHaveLength(2);
    expect(await screen.findAllByText('true')).toHaveLength(2);
    expect(await screen.findAllByText('26 hours, 38 seconds')).toHaveLength(2);
  });
});
