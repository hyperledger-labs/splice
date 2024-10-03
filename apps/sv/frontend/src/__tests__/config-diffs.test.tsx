// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  checkAmuletRulesExpectedConfigDiffsHTML,
  checkDsoRulesExpectedConfigDiffsHTML,
} from 'common-test-utils';
import { test, expect, describe } from 'vitest';

import App from '../App';
import { SvConfigProvider } from '../utils';
import {
  svPartyId,
  getExpectedAmuletRulesConfigDiffsHTML,
  getExpectedDsoRulesConfigDiffsHTML,
} from './mocks/constants';

const AppWithConfig = () => {
  return (
    <SvConfigProvider>
      <App />
    </SvConfigProvider>
  );
};

describe('SV user can', () => {
  // TODO(tech-debt): create unique login function
  test('login and see the SV party ID', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    const button = screen.getByRole('button', { name: 'Log In' });
    user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });
});

describe('SV can see config diffs of CRARC_AddFutureAmuletConfigSchedule', () => {
  const action = 'CRARC_AddFutureAmuletConfigSchedule';

  test('while creating a vote request.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
    expect(await screen.findByText('Governance')).toBeDefined();

    const dropdown = screen.getByTestId('display-actions');
    expect(dropdown).toBeDefined();
    fireEvent.change(dropdown!, { target: { value: action } });

    expect(await screen.findByText('Config diffs')).toBeDefined();

    // current comparison + 1 in-flight vote request
    checkNumberNumberOfDiffs(2);
  });

  test('in the action needed section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Action Needed', action, user);

    const mockHtmlContent = getExpectedAmuletRulesConfigDiffsHTML('4815162342', '222.2');
    checkAmuletRulesExpectedConfigDiffsHTML(mockHtmlContent, 0);

    // current comparison
    checkNumberNumberOfDiffs(1);
  });

  test('in the planned section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Planned', action, user);

    const mockHtmlContent = getExpectedAmuletRulesConfigDiffsHTML('4815162342', '1.03');
    checkAmuletRulesExpectedConfigDiffsHTML(mockHtmlContent, 0);

    // current comparison
    checkNumberNumberOfDiffs(1);
  });

  test('in the executed section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Executed', action, user);

    //TODO(#14813): when an action is executed, the AmuletConfigSchedule is updated and actualized to now, therefore the diff is empty for the first change
    screen.getByTestId('stringify-display');

    // current comparison against vote result
    checkNumberNumberOfDiffs(1);
  });

  test('in the rejected section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Rejected', action, user);

    screen.getByTestId('stringify-display');

    // current comparison against vote result
    checkNumberNumberOfDiffs(1);
  });
});

describe('SV can see config diffs of SRARC_SetConfig', () => {
  const action = 'SRARC_SetConfig';

  test('while creating a vote request.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
    expect(await screen.findByText('Governance')).toBeDefined();

    const dropdown = screen.getByTestId('display-actions');
    expect(dropdown).toBeDefined();
    fireEvent.change(dropdown!, { target: { value: action } });

    const checkBox = screen.getByTestId('enable-next-scheduled-domain-upgrade');
    await user.click(checkBox);

    expect(await screen.findByText('Config diffs')).toBeDefined();

    // current comparison + 1 in-flight vote request
    checkNumberNumberOfDiffs(3);
  });

  test('in the action needed section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Action Needed', action, user);
    const mockHtmlContent = getExpectedDsoRulesConfigDiffsHTML('1600', '2100');
    checkDsoRulesExpectedConfigDiffsHTML(mockHtmlContent);

    // current comparison
    checkNumberNumberOfDiffs(2);
  });

  test('of a SetConfig vote result in the executed section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Executed', 'SRARC_SetConfig', user);

    // when an action is executed, the AmuletConfigSchedule is updated and actualized to now, therefore the diff is empty
    const mockHtmlContent = getExpectedDsoRulesConfigDiffsHTML('1600', '1800');
    checkDsoRulesExpectedConfigDiffsHTML(mockHtmlContent);

    // current comparison against vote result
    checkNumberNumberOfDiffs(1);
  });

  test('in the rejected section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Rejected', action, user);

    const mockHtmlContent = getExpectedDsoRulesConfigDiffsHTML('1600', '2000');
    checkDsoRulesExpectedConfigDiffsHTML(mockHtmlContent);

    // current comparison against vote result
    checkNumberNumberOfDiffs(1);
  });
});

function checkNumberNumberOfDiffs(expected: number): void {
  // eslint-disable-next-line testing-library/no-node-access
  const accordionElements = document.querySelectorAll(
    '.MuiButtonBase-root.MuiAccordionSummary-root.MuiAccordionSummary-gutters'
  );
  expect(accordionElements.length).toBe(expected);
}

async function goToGovernanceTabAndClickOnAction(
  tableType: string,
  action: string,
  user: ReturnType<typeof userEvent.setup>
): Promise<void> {
  expect(await screen.findByText('Governance')).toBeDefined();
  await user.click(screen.getByText('Governance'));

  expect(await screen.findByText('Vote Requests')).toBeDefined();
  expect(await screen.findByText('Governance')).toBeDefined();

  expect(await screen.findByText(tableType)).toBeDefined();
  await user.click(screen.getByText(tableType));

  expect(await screen.findAllByText(action)).toBeDefined();
  await user.click(screen.getAllByText(action)[0]);
}
