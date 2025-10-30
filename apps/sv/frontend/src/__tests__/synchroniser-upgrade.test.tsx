// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { dsoInfo } from '@lfdecentralizedtrust/splice-common-test-handlers';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import timezone from 'dayjs/plugin/timezone';
import { rest } from 'msw';
import { test, expect, describe } from 'vitest';

import App from '../App';
import { SvConfigProvider } from '../utils';
import { svPartyId } from './mocks/constants';
import { server, svUrl } from './setup/setup';
import { changeAction } from './helpers';

dayjs.extend(timezone);

type UserEvent = ReturnType<typeof userEvent.setup>;

const AppWithConfig = () => {
  return (
    <SvConfigProvider>
      <App />
    </SvConfigProvider>
  );
};

const dsoInfoWithoutSynchronizerUpgrade = dsoInfo;
const dsoInfoWithSynchronizerUpgrade = JSON.parse(
  JSON.stringify(dsoInfoWithoutSynchronizerUpgrade)
);
dsoInfoWithSynchronizerUpgrade.dso_rules.contract.payload.config.nextScheduledSynchronizerUpgrade =
  { time: '2055-04-30T15:37:48Z', migrationId: '1' };

const dateFormat = 'YYYY-MM-DD HH:mm';
// This format should only be used when working with a datetime already in utc
const syncPauseTimeDateFormat = 'YYYY-MM-DDTHH:mm:ss[Z]';

async function fillOutForm(user: UserEvent) {
  const unclaimedRewardsThresholdInput = screen.getByTestId('numUnclaimedRewardsThreshold-value');
  await user.type(unclaimedRewardsThresholdInput, '111');

  const summaryInput = screen.getByTestId('create-reason-summary');
  await user.type(summaryInput, 'summaryABC');

  const urlInput = screen.getByTestId('create-reason-url');
  await user.type(urlInput, 'https://vote-request-url.com');
}

describe('SV user can', () => {
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

  test('set next scheduled synchronizer upgrade', async () => {
    server.use(
      rest.get(`${svUrl}/v0/dso`, (_, res, ctx) => {
        return res(ctx.json(dsoInfoWithoutSynchronizerUpgrade));
      })
    );

    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    await user.click(screen.getByText('Governance'));

    await changeAction();

    expect(screen.queryByText('nextScheduledSynchronizerUpgrade.time')).toBeNull();
    expect(await screen.findByText('nextScheduledSynchronizerUpgrade')).toBeDefined();

    const checkBox = screen.getByTestId('enable-next-scheduled-domain-upgrade');
    await user.click(checkBox);

    expect(await screen.findByText('nextScheduledSynchronizerUpgrade.time')).toBeDefined();
  });

  test('submit vote request with new valid synchronizer upgrade time', async () => {
    server.use(
      rest.get(`${svUrl}/v0/dso`, (_, res, ctx) => {
        return res(ctx.json(dsoInfoWithoutSynchronizerUpgrade));
      })
    );

    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    await user.click(screen.getByText('Governance'));

    await changeAction();

    const synchronizerUpgradeCheckBox = screen.getByTestId('enable-next-scheduled-domain-upgrade');
    await user.click(synchronizerUpgradeCheckBox);

    await fillOutForm(user);

    const expirationDate = screen
      .getByTestId('datetime-picker-vote-request-expiration')
      .getAttribute('value');
    expect(expirationDate).toBeDefined();

    const expirationDateDayjs = dayjs(expirationDate);
    const newSyncUpgradeTime = expirationDateDayjs
      .utc()
      .add(1, 'minute')
      .format(syncPauseTimeDateFormat);

    const nextScheduledSynchronizerUpgradeTime = screen.getByTestId(
      'nextScheduledSynchronizerUpgrade.time-value'
    );

    fireEvent.change(nextScheduledSynchronizerUpgradeTime, {
      target: { value: newSyncUpgradeTime },
    });

    expect(
      screen.getByTestId('create-voterequest-submit-button').getAttribute('disabled')
    ).toBeDefined();
  });

  test('submit vote request with existing and unchanged synchronizer upgrade time', async () => {
    server.use(
      rest.get(`${svUrl}/v0/dso`, (_, res, ctx) => {
        return res(ctx.json(dsoInfoWithSynchronizerUpgrade));
      })
    );

    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    await user.click(screen.getByText('Governance'));

    await changeAction();
    await fillOutForm(user);

    const disabled = screen
      .queryByTestId('create-voterequest-submit-button')
      ?.getAttribute('disabled');

    expect(disabled).toBeOneOf([null, '']);
  });

  test('not submit vote request if new synchronizer upgrade time is before expiry', async () => {
    server.use(
      rest.get(`${svUrl}/v0/dso`, (_, res, ctx) => {
        return res(ctx.json(dsoInfoWithoutSynchronizerUpgrade));
      })
    );

    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    await user.click(screen.getByText('Governance'));

    await changeAction();
    await fillOutForm(user);

    const effectiveAtThresholdCheckBox = screen.getByTestId('checkbox-set-effective-at-threshold');
    await user.click(effectiveAtThresholdCheckBox);

    const synchronizerUpgradeCheckBox = screen.getByTestId('enable-next-scheduled-domain-upgrade');
    await user.click(synchronizerUpgradeCheckBox);

    const expirationDate = screen
      .getByTestId('datetime-picker-vote-request-expiration')
      .getAttribute('value');
    expect(expirationDate).toBeDefined();

    const expirationDateDayjs = dayjs(expirationDate);
    const invalidUpgradeTime = expirationDateDayjs.subtract(1, 'minute').format(dateFormat);
    const validUpgradeTime = expirationDateDayjs.utc().add(1, 'minute').format(dateFormat);

    const nextScheduledSynchronizerUpgradeTime = screen.getByTestId(
      'nextScheduledSynchronizerUpgrade.time-value'
    );

    fireEvent.change(nextScheduledSynchronizerUpgradeTime, {
      target: { value: invalidUpgradeTime },
    });

    expect(
      screen.getByTestId('create-voterequest-submit-button').getAttribute('disabled')
    ).toBeDefined();

    fireEvent.change(nextScheduledSynchronizerUpgradeTime, {
      target: { value: validUpgradeTime },
    });

    expect(
      screen.queryByTestId('create-voterequest-submit-button')?.getAttribute('disabled')
    ).toBeNull();
  });

  test('not submit vote request if synchronizer upgrade time is changed and is before expiry and effective at threshold', async () => {
    server.use(
      rest.get(`${svUrl}/v0/dso`, (_, res, ctx) => {
        return res(ctx.json(dsoInfoWithSynchronizerUpgrade));
      })
    );

    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    await user.click(screen.getByText('Governance'));

    await changeAction();
    await fillOutForm(user);

    const effectiveDateComponent = screen.getByTestId('datetime-picker-vote-request-expiration');
    const effectiveDate = effectiveDateComponent.getAttribute('value');
    const effectiveDateDayjs = dayjs(effectiveDate);
    const effectiveDateMinus1Minute = effectiveDateDayjs.subtract(1, 'minute').format(dateFormat);

    fireEvent.change(effectiveDateComponent, {
      target: { value: effectiveDateMinus1Minute },
    });

    // effective date above is invalid but shouldn;t matter because we checked this box
    const effectiveAtThresholdCheckBox = screen.getByTestId('checkbox-set-effective-at-threshold');
    await user.click(effectiveAtThresholdCheckBox);

    const synchronizerUpgradeCheckBox = screen.getByTestId('enable-next-scheduled-domain-upgrade');
    await user.click(synchronizerUpgradeCheckBox);

    const expirationDate = screen
      .getByTestId('datetime-picker-vote-request-expiration')
      .getAttribute('value');
    expect(expirationDate).toBeDefined();

    const expirationDateDayjs = dayjs(expirationDate);
    const invalidUpgradeTime = expirationDateDayjs.utc().subtract(1, 'minute').format(dateFormat);

    const nextScheduledSynchronizerUpgradeTime = screen.getByTestId(
      'nextScheduledSynchronizerUpgrade.time-value'
    );

    fireEvent.change(nextScheduledSynchronizerUpgradeTime, {
      target: { value: invalidUpgradeTime },
    });

    expect(
      screen.getByTestId('create-voterequest-submit-button').getAttribute('disabled')
    ).toBeDefined();
  });

  test('not submit vote request if synchronizer upgrade time is changed and is before effective date', async () => {
    server.use(
      rest.get(`${svUrl}/v0/dso`, (_, res, ctx) => {
        return res(ctx.json(dsoInfoWithSynchronizerUpgrade));
      })
    );

    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    await user.click(screen.getByText('Governance'));

    await changeAction();
    await fillOutForm(user);

    const synchronizerUpgradeCheckBox = screen.getByTestId('enable-next-scheduled-domain-upgrade');
    await user.click(synchronizerUpgradeCheckBox);

    const effectiveDate = screen
      .getByTestId('datetime-picker-vote-request-effectivity')
      .getAttribute('value');
    expect(effectiveDate).toBeDefined();

    const effectiveDateDayjs = dayjs(effectiveDate);
    const invalidUpgradeTime = effectiveDateDayjs.utc().subtract(1, 'minute').format(dateFormat);
    const validUpgradeTime = effectiveDateDayjs.add(1, 'minute').format(dateFormat);

    const nextScheduledSynchronizerUpgradeTime = screen.getByTestId(
      'nextScheduledSynchronizerUpgrade.time-value'
    );

    fireEvent.change(nextScheduledSynchronizerUpgradeTime, {
      target: { value: invalidUpgradeTime },
    });

    expect(
      screen.getByTestId('create-voterequest-submit-button').getAttribute('disabled')
    ).toBeDefined();

    fireEvent.change(nextScheduledSynchronizerUpgradeTime, {
      target: { value: validUpgradeTime },
    });

    const disabled = screen
      .queryByTestId('create-voterequest-submit-button')
      ?.getAttribute('disabled');

    expect(disabled).toBeOneOf([null, '']);
  });

  test(
    'make changes with different timezones',
    async () => {
      server.use(
        rest.get(`${svUrl}/v0/dso`, (_, res, ctx) => {
          return res(ctx.json(dsoInfoWithoutSynchronizerUpgrade));
        })
      );

      const user = userEvent.setup();
      render(<AppWithConfig />);

      expect(await screen.findByText('Log In')).toBeDefined();

      const input = screen.getByRole('textbox');
      await user.type(input, 'sv1');

      await user.click(screen.getByText('Governance'));

      await changeAction();
      await fillOutForm(user);

      const effectiveAtThresholdCheckBox = screen.getByTestId(
        'checkbox-set-effective-at-threshold'
      );
      await user.click(effectiveAtThresholdCheckBox);

      const synchronizerUpgradeCheckBox = screen.getByTestId(
        'enable-next-scheduled-domain-upgrade'
      );
      await user.click(synchronizerUpgradeCheckBox);

      const expirationDate = screen
        .getByTestId('datetime-picker-vote-request-expiration')
        .getAttribute('value');
      expect(expirationDate).toBeDefined();

      // FYI: Tests are running in UTC+2 timezone by default
      const expirationDateDayjs = dayjs(expirationDate);

      const invalidUpgradeTime = expirationDateDayjs
        .utc()
        .subtract(1, 'minute')
        .format(syncPauseTimeDateFormat);

      const sameUpgradeTime = expirationDateDayjs
        .utc()
        .add(1, 'minute')
        .format(syncPauseTimeDateFormat);

      const validUpgradeTime = expirationDateDayjs
        .utc()
        .add(1, 'minute')
        .format(syncPauseTimeDateFormat);

      const nextScheduledSynchronizerUpgradeTime = screen.getByTestId(
        'nextScheduledSynchronizerUpgrade.time-value'
      );

      fireEvent.change(nextScheduledSynchronizerUpgradeTime, {
        target: { value: invalidUpgradeTime },
      });

      expect(
        screen.getByTestId('create-voterequest-submit-button').getAttribute('disabled')
      ).toBeDefined();

      fireEvent.change(nextScheduledSynchronizerUpgradeTime, {
        target: { value: sameUpgradeTime },
      });

      expect(
        screen.getByTestId('create-voterequest-submit-button').getAttribute('disabled')
      ).toBeDefined();

      fireEvent.change(nextScheduledSynchronizerUpgradeTime, {
        target: { value: validUpgradeTime },
      });

      expect(
        screen.queryByTestId('create-voterequest-submit-button')?.getAttribute('disabled')
      ).toBeNull();
    },
    {
      timeout: 10000,
    }
  );
});
