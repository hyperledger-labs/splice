// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  getExpectedAmuletRulesConfigDiffsHTML,
  getExpectedDsoRulesConfigDiffsHTML,
} from '@lfdecentralizedtrust/splice-common-test-handlers';
import {
  checkAmuletRulesExpectedConfigDiffsHTML,
  checkDsoRulesExpectedConfigDiffsHTML,
} from '@lfdecentralizedtrust/splice-common-test-utils';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { test, expect, describe } from 'vitest';

import App from '../App';
import { SvConfigProvider } from '../utils';
import { svPartyId } from './mocks/constants';
import { changeAction } from './helpers';

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
    await user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });
});

describe('SV can see AmuletRules config diffs', () => {
  // Note: here we test each VoteRequest phases using both the deprecated action and the actual action
  // the underlying components and logic are the same.
  const deprecatedAction = 'CRARC_AddFutureAmuletConfigSchedule';
  const action = 'CRARC_SetConfig';

  test('while creating a vote request.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
    expect(await screen.findByText('Governance')).toBeDefined();

    await changeAction(action);

    const input = screen.getByTestId('transferConfig.createFee.fee-value');
    await userEvent.type(input, '42');

    expect(await screen.findByText('Config diffs')).toBeDefined();

    // current comparison
    checkNumberNumberOfDiffs(1);
  });

  test('in the action needed section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Action Needed', deprecatedAction, user, 1);

    const mockHtmlContent = getExpectedAmuletRulesConfigDiffsHTML('4815162342', '222.2');
    await checkAmuletRulesExpectedConfigDiffsHTML(mockHtmlContent, 0);

    // current comparison
    checkNumberNumberOfDiffs(1);
  });

  test('in the executed section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Executed', deprecatedAction, user);

    //TODO(#934): when an action is executed, the AmuletConfigSchedule is updated and actualized to now, therefore the diff is empty for the first change
    await screen.findByTestId('stringify-display');

    // current comparison against vote result
    checkNumberNumberOfDiffs(1);
  });

  test('in the rejected section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Rejected', deprecatedAction, user);

    await screen.findByTestId('stringify-display');

    // current comparison against vote result
    checkNumberNumberOfDiffs(1);
  });
});

describe('SV can see DsoRules config diffs', () => {
  const action = 'SRARC_SetConfig';

  test('while creating a vote request.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
    expect(await screen.findByText('Governance')).toBeDefined();

    await changeAction(action);

    const input = screen.getByTestId(
      'decentralizedSynchronizer.synchronizers.0.1.acsCommitmentReconciliationInterval-value'
    );
    await user.clear(input);
    await user.type(input, '481516');

    expect(await screen.findByText('Config diffs')).toBeDefined();

    // current comparison
    checkNumberNumberOfDiffs(1);
  });

  test('in the action needed section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Action Needed', action, user, 2);
    const mockHtmlContent = getExpectedDsoRulesConfigDiffsHTML('1600', '2100');
    await checkDsoRulesExpectedConfigDiffsHTML(mockHtmlContent);

    // current comparison
    checkNumberNumberOfDiffs(1);
  });

  test('of a SetConfig vote result in the executed section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Executed', action, user, 1);
    const mockHtmlContent = getExpectedDsoRulesConfigDiffsHTML('100', '2200', true);
    await checkDsoRulesExpectedConfigDiffsHTML(mockHtmlContent, 0);

    // current comparison against vote result
    checkNumberNumberOfDiffs(1);
  });

  test(
    'of a SetConfig vote result in the executed section 2.',
    async () => {
      const user = userEvent.setup();
      render(<AppWithConfig />);

      await goToGovernanceTabAndClickOnAction('Executed', action, user, 2);
      const mockHtmlContent = getExpectedDsoRulesConfigDiffsHTML('1900', '1800', true);
      await checkDsoRulesExpectedConfigDiffsHTML(mockHtmlContent, 0);

      checkNumberNumberOfDiffs(1);
    },
    { retry: 3 }
  );

  test('in the rejected section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Rejected', action, user, 1);
    const mockHtmlContent = getExpectedDsoRulesConfigDiffsHTML('20', '2000', true);
    await checkDsoRulesExpectedConfigDiffsHTML(mockHtmlContent, 0);

    checkNumberNumberOfDiffs(1);
  });
});

function checkNumberNumberOfDiffs(expected: number): void {
  const accordionElements = document.querySelectorAll(
    '.MuiButtonBase-root.MuiAccordionSummary-root.MuiAccordionSummary-gutters'
  );
  expect(accordionElements.length).toBe(expected);
}

async function goToGovernanceTabAndClickOnAction(
  tableType: string,
  action: string,
  user: ReturnType<typeof userEvent.setup>,
  index: number = 0
): Promise<void> {
  expect(await screen.findByText('Governance')).toBeDefined();
  await user.click(screen.getByText('Governance'));

  expect(await screen.findByText('Vote Requests')).toBeDefined();
  expect(await screen.findByText('Governance')).toBeDefined();

  const button = await screen.findByText(tableType);
  expect(button).toBeDefined();
  await user.click(screen.getByText(tableType));

  expect(button.getAttribute('aria-selected')).toBe('true');

  expect(await screen.findAllByText(action)).toBeDefined();

  const row = document.querySelector(`[data-id="${index}"]`);

  await user.click(row!);
}
