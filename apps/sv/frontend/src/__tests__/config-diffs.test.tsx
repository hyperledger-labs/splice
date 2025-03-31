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
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { test, expect, describe } from 'vitest';

import App from '../App';
import { SvConfigProvider } from '../utils';
import { svPartyId } from './mocks/constants';

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
    await checkAmuletRulesExpectedConfigDiffsHTML(mockHtmlContent, 0);

    // current comparison
    checkNumberNumberOfDiffs(1);
  });

  test('in the planned section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Planned', action, user);

    const mockHtmlContent = getExpectedAmuletRulesConfigDiffsHTML('4815162342', '1.03');
    await checkAmuletRulesExpectedConfigDiffsHTML(mockHtmlContent, 0);

    // current comparison
    checkNumberNumberOfDiffs(1);
  });

  test('in the executed section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Executed', action, user);

    //TODO(#14813): when an action is executed, the AmuletConfigSchedule is updated and actualized to now, therefore the diff is empty for the first change
    await screen.findByTestId('stringify-display');

    // current comparison against vote result
    checkNumberNumberOfDiffs(1);
  });

  test('in the rejected section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Rejected', action, user);

    await screen.findByTestId('stringify-display');

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

    await goToGovernanceTabAndClickOnAction('Action Needed', action, user, 1);
    const mockHtmlContent = getExpectedDsoRulesConfigDiffsHTML('1600', '2100');
    await checkDsoRulesExpectedConfigDiffsHTML(mockHtmlContent);

    // current comparison
    checkNumberNumberOfDiffs(2);
  });

  test('of a SetConfig vote result in the executed section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Executed', action, user, 1);

    const mockHtmlContent = getExpectedDsoRulesConfigDiffsHTML('1800', '2200');

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

      const mockJsonContent = getMockJsonContentForDsoRules('1800');

      await checkDsoRulesExpectedConfigDiffsHTML(mockJsonContent, 0, true);

      checkNumberNumberOfDiffs(1);
    },
    { retry: 3 }
  );

  test('in the rejected section.', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await goToGovernanceTabAndClickOnAction('Rejected', action, user, 1);

    const mockHtmlContent = getMockJsonContentForDsoRules('2000');

    await checkDsoRulesExpectedConfigDiffsHTML(mockHtmlContent, 0, true);

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

  // eslint-disable-next-line testing-library/no-node-access
  const row = document.querySelector(`[data-id="${index}"]`);

  await user.click(row!);
}

function getMockJsonContentForDsoRules(acsCommitmentReconciliationInterval: string): string {
  return (
    '{\n' +
    '  "numUnclaimedRewardsThreshold": "10",\n' +
    '  "numMemberTrafficContractsThreshold": "5",\n' +
    '  "actionConfirmationTimeout": {\n' +
    '    "microseconds": "3600000000"\n' +
    '  },\n' +
    '  "svOnboardingRequestTimeout": {\n' +
    '    "microseconds": "3600000000"\n' +
    '  },\n' +
    '  "svOnboardingConfirmedTimeout": {\n' +
    '    "microseconds": "3600000000"\n' +
    '  },\n' +
    '  "voteRequestTimeout": {\n' +
    '    "microseconds": "604800000000"\n' +
    '  },\n' +
    '  "dsoDelegateInactiveTimeout": {\n' +
    '    "microseconds": "70000000"\n' +
    '  },\n' +
    '  "synchronizerNodeConfigLimits": {\n' +
    '    "cometBft": {\n' +
    '      "maxNumCometBftNodes": "2",\n' +
    '      "maxNumGovernanceKeys": "2",\n' +
    '      "maxNumSequencingKeys": "2",\n' +
    '      "maxNodeIdLength": "50",\n' +
    '      "maxPubKeyLength": "256"\n' +
    '    }\n' +
    '  },\n' +
    '  "maxTextLength": "1024",\n' +
    '  "decentralizedSynchronizer": {\n' +
    '    "synchronizers": [\n' +
    '      [\n' +
    '        "global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37",\n' +
    '        {\n' +
    '          "state": "DS_Operational",\n' +
    '          "cometBftGenesisJson": "TODO(#4900): share CometBFT genesis.json of sv1 via DsoRules config.",\n' +
    `          "acsCommitmentReconciliationInterval": "${acsCommitmentReconciliationInterval}"\n` +
    '        }\n' +
    '      ]\n' +
    '    ],\n' +
    '    "lastSynchronizerId": "global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37",\n' +
    '    "activeSynchronizerId": "global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37"\n' +
    '  },\n' +
    '  "nextScheduledSynchronizerUpgrade": null\n' +
    '}'
  );
}
