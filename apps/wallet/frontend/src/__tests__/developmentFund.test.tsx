// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { rest } from 'msw';
import { describe, expect, test, vi } from 'vitest';

import App from '../App';
import * as developmentFundAllocationFormHook from '../hooks/useDevelopmentFundAllocationForm';
import * as developmentFundHook from '../hooks/useDevelopmentFund';
import { WalletConfigProvider } from '../utils/config';
import { alicePartyId, amuletRules, bobPartyId, userLogin } from './mocks/constants';
import { server } from './setup/setup';
import dayjs from 'dayjs';
import BigNumber from 'bignumber.js';

const walletUrl = window.splice_config.services.validator.url;

const loginAndOpenDevelopmentFund = async () => {
  const user = userEvent.setup();
  window.localStorage.clear();
  window.sessionStorage.clear();

  render(
    <WalletConfigProvider>
      <App />
    </WalletConfigProvider>
  );

  await screen.findByText('Log In');
  await user.type(screen.getByRole('textbox'), userLogin);
  await user.click(screen.getByRole('button', { name: 'Log In' }));

  const developmentFundLink = await screen.findByRole('link', { name: 'Development Fund' });
  await user.click(developmentFundLink);

  return { user };
};

const buildAllocationFormMock = (
  overrides: Partial<
    ReturnType<typeof developmentFundAllocationFormHook.useDevelopmentFundAllocationForm>
  > = {}
): ReturnType<typeof developmentFundAllocationFormHook.useDevelopmentFundAllocationForm> => ({
  formKey: 0,
  error: null,
  beneficiary: alicePartyId,
  setBeneficiary: vi.fn(),
  amount: '1',
  setAmount: vi.fn(),
  expiresAt: dayjs().add(2, 'day'),
  setExpiresAt: vi.fn(),
  reason: 'Valid allocation',
  setReason: vi.fn(),
  amountNum: new BigNumber(1),
  isAmountValid: true,
  amountExceedsAvailable: false,
  isExpiryValid: true,
  expiryError: undefined,
  isReasonValid: true,
  isValid: true,
  resetForm: vi.fn(),
  allocateMutation: {
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<
    typeof developmentFundAllocationFormHook.useDevelopmentFundAllocationForm
  >['allocateMutation'],
  isFundManager: true,
  unclaimedTotal: new BigNumber(100),
  ...overrides,
});

describe('Development Fund page', () => {
  test('shows alert when user is not fund manager', async () => {
    await loginAndOpenDevelopmentFund();

    expect(
      await screen.findByText(
        /Your party is not the development fund manager designated by the CF foundation\./
      )
    ).toBeDefined();
  });

  test('applies allocation amount validation', async () => {
    server.use(
      rest.get(`${walletUrl}/v0/scan-proxy/amulet-rules`, (_, res, ctx) => {
        return res(
          ctx.json({
            ...amuletRules,
            amulet_rules: {
              ...amuletRules.amulet_rules,
              contract: {
                ...amuletRules.amulet_rules.contract,
                payload: {
                  ...amuletRules.amulet_rules.contract.payload,
                  configSchedule: {
                    ...amuletRules.amulet_rules.contract.payload.configSchedule,
                    initialValue: {
                      ...amuletRules.amulet_rules.contract.payload.configSchedule.initialValue,
                      optDevelopmentFundManager: alicePartyId,
                    },
                  },
                },
              },
            },
          })
        );
      }),
      rest.get(`${walletUrl}/v0/scan-proxy/unclaimed-development-fund-coupons`, (_, res, ctx) => {
        return res(
          ctx.json({
            unclaimed_development_fund_coupons: [
              {
                contract: {
                  payload: {
                    amount: '10.0',
                  },
                },
                domain_id: 'domain-0',
              },
            ],
          })
        );
      })
    );

    const { user } = await loginAndOpenDevelopmentFund();

    expect(
      await screen.findByRole('heading', { name: 'Development Fund Allocation' })
    ).toBeDefined();

    const amountInput = screen.getByRole('textbox', { name: 'amount' });
    await user.type(amountInput, '11');

    expect(amountInput).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: 'Allocate' })).toBeDisabled();
  });

  test('shows amount helper text when allocation amount is zero', async () => {
    server.use(
      rest.get(`${walletUrl}/v0/scan-proxy/amulet-rules`, (_, res, ctx) => {
        return res(
          ctx.json({
            ...amuletRules,
            amulet_rules: {
              ...amuletRules.amulet_rules,
              contract: {
                ...amuletRules.amulet_rules.contract,
                payload: {
                  ...amuletRules.amulet_rules.contract.payload,
                  configSchedule: {
                    ...amuletRules.amulet_rules.contract.payload.configSchedule,
                    initialValue: {
                      ...amuletRules.amulet_rules.contract.payload.configSchedule.initialValue,
                      optDevelopmentFundManager: alicePartyId,
                    },
                  },
                },
              },
            },
          })
        );
      }),
      rest.get(`${walletUrl}/v0/scan-proxy/unclaimed-development-fund-coupons`, (_, res, ctx) => {
        return res(
          ctx.json({
            unclaimed_development_fund_coupons: [
              {
                contract: {
                  payload: {
                    amount: '10.0',
                  },
                },
                domain_id: 'domain-0',
              },
            ],
          })
        );
      })
    );

    const { user } = await loginAndOpenDevelopmentFund();

    expect(
      await screen.findByRole('heading', { name: 'Development Fund Allocation' })
    ).toBeDefined();

    const amountInput = screen.getByRole('textbox', { name: 'amount' });
    await user.type(amountInput, '0');

    expect(await screen.findByText('Amount must be greater than 0')).toBeDefined();
    expect(amountInput).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: 'Allocate' })).toBeDisabled();
  });

  test('applies allocation expiration date validation', async () => {
    server.use(
      rest.get(`${walletUrl}/v0/scan-proxy/amulet-rules`, (_, res, ctx) => {
        return res(
          ctx.json({
            ...amuletRules,
            amulet_rules: {
              ...amuletRules.amulet_rules,
              contract: {
                ...amuletRules.amulet_rules.contract,
                payload: {
                  ...amuletRules.amulet_rules.contract.payload,
                  configSchedule: {
                    ...amuletRules.amulet_rules.contract.payload.configSchedule,
                    initialValue: {
                      ...amuletRules.amulet_rules.contract.payload.configSchedule.initialValue,
                      optDevelopmentFundManager: alicePartyId,
                    },
                  },
                },
              },
            },
          })
        );
      })
    );

    const { user } = await loginAndOpenDevelopmentFund();

    expect(
      await screen.findByRole('heading', { name: 'Development Fund Allocation' })
    ).toBeDefined();

    const invalidExpirationDate = dayjs().subtract(2, 'day').format('MM/DD/YYYY hh:mm A');
    const expiresAtInput = screen.getByRole('textbox', { name: /expires at/i });
    await user.type(expiresAtInput, invalidExpirationDate);
    await user.tab();

    await waitFor(() => expect(expiresAtInput).toHaveAttribute('aria-invalid', 'true'));
    expect(screen.getByRole('button', { name: 'Allocate' })).toBeDisabled();
  });

  test('renders development fund history table view', async () => {
    server.use(
      rest.get(`${walletUrl}/v0/scan-proxy/amulet-rules`, (_, res, ctx) => {
        return res(
          ctx.json({
            ...amuletRules,
            amulet_rules: {
              ...amuletRules.amulet_rules,
              contract: {
                ...amuletRules.amulet_rules.contract,
                payload: {
                  ...amuletRules.amulet_rules.contract.payload,
                  configSchedule: {
                    ...amuletRules.amulet_rules.contract.payload.configSchedule,
                    initialValue: {
                      ...amuletRules.amulet_rules.contract.payload.configSchedule.initialValue,
                      optDevelopmentFundManager: alicePartyId,
                    },
                  },
                },
              },
            },
          })
        );
      }),
      rest.get(`${walletUrl}/v0/wallet/development-fund-coupons/history`, (_, res, ctx) => {
        return res(
          ctx.json({
            development_fund_coupon_history: [
              {
                createdAt: '2026-01-01T10:00:00.000Z',
                archivedAt: '2026-01-02T11:00:00.000Z',
                beneficiary: alicePartyId,
                fund_manager: bobPartyId,
                amount: '3.0',
                expiresAt: '2026-01-10T11:00:00.000Z',
                reason: 'Infrastructure improvements',
                status: 'withdrawn',
                rejection_or_withdrawal_reason: 'No longer needed',
              },
            ],
          })
        );
      })
    );

    await loginAndOpenDevelopmentFund();

    expect(await screen.findByRole('heading', { name: 'Coupon History' })).toBeDefined();
    expect(await screen.findByText('Infrastructure improvements')).toBeDefined();
    expect(await screen.findByText('No longer needed')).toBeDefined();
  });

  test('renders empty development fund history table view', async () => {
    server.use(
      rest.get(`${walletUrl}/v0/scan-proxy/amulet-rules`, (_, res, ctx) => {
        return res(
          ctx.json({
            ...amuletRules,
            amulet_rules: {
              ...amuletRules.amulet_rules,
              contract: {
                ...amuletRules.amulet_rules.contract,
                payload: {
                  ...amuletRules.amulet_rules.contract.payload,
                  configSchedule: {
                    ...amuletRules.amulet_rules.contract.payload.configSchedule,
                    initialValue: {
                      ...amuletRules.amulet_rules.contract.payload.configSchedule.initialValue,
                      optDevelopmentFundManager: alicePartyId,
                    },
                  },
                },
              },
            },
          })
        );
      }),
      rest.get(`${walletUrl}/v0/wallet/development-fund-coupons/history`, (_, res, ctx) => {
        return res(
          ctx.json({
            development_fund_coupon_history: [],
          })
        );
      })
    );

    await loginAndOpenDevelopmentFund();

    expect(await screen.findByRole('heading', { name: 'Coupon History' })).toBeDefined();
    expect(await screen.findByText('No history events found')).toBeDefined();
  });

  test('renders unclaimed development fund allocations table view', async () => {
    server.use(
      rest.get(`${walletUrl}/v0/scan-proxy/amulet-rules`, (_, res, ctx) => {
        return res(
          ctx.json({
            ...amuletRules,
            amulet_rules: {
              ...amuletRules.amulet_rules,
              contract: {
                ...amuletRules.amulet_rules.contract,
                payload: {
                  ...amuletRules.amulet_rules.contract.payload,
                  configSchedule: {
                    ...amuletRules.amulet_rules.contract.payload.configSchedule,
                    initialValue: {
                      ...amuletRules.amulet_rules.contract.payload.configSchedule.initialValue,
                      optDevelopmentFundManager: alicePartyId,
                    },
                  },
                },
              },
            },
          })
        );
      })
    );

    await loginAndOpenDevelopmentFund();

    expect(
      await screen.findByRole('heading', { name: 'Unclaimed Development Fund Allocations' })
    ).toBeDefined();
    expect(await screen.findByText('No development fund allocations found')).toBeDefined();
  });

  test('renders unclaimed development fund allocations table with items', async () => {
    const useDevelopmentFundSpy = vi
      .spyOn(developmentFundHook, 'useDevelopmentFund')
      .mockReturnValue({
        primaryParty: alicePartyId,
        isFundManager: true,
        isLoading: false,
        coupons: {
          coupons: [
            {
              id: 'dev-fund-coupon-1',
              createdAt: new Date('2026-01-01T10:00:00.000Z'),
              fundManager: alicePartyId,
              beneficiary: alicePartyId,
              amount: new BigNumber(2.5),
              expiresAt: new Date('2026-01-10T11:00:00.000Z'),
              reason: 'Protocol upgrade',
            },
          ],
          isLoading: false,
          isError: false,
          error: null,
          hasNextPage: false,
          hasPreviousPage: false,
          currentPage: 1,
          goToNextPage: vi.fn(),
          goToPreviousPage: vi.fn(),
        },
        history: {
          historyEvents: [],
          isLoadingHistory: false,
          isHistoryError: false,
          historyError: null,
          hasNextHistoryPage: false,
          hasPreviousHistoryPage: false,
          currentHistoryPage: 1,
          goToNextHistoryPage: vi.fn(),
          goToPreviousHistoryPage: vi.fn(),
        },
        unclaimedTotal: new BigNumber(10),
        isLoadingUnclaimedTotal: false,
        isUnclaimedTotalError: false,
        unclaimedTotalError: null,
        invalidateAll: vi.fn(),
      });

    await loginAndOpenDevelopmentFund();

    expect(
      await screen.findByRole('heading', { name: 'Unclaimed Development Fund Allocations' })
    ).toBeDefined();
    expect(
      await screen.findByText(
        /Your party is the development fund manager designated by the CF foundation\./
      )
    ).toBeDefined();
    expect(await screen.findByText('Protocol upgrade')).toBeDefined();
    expect(await screen.findByText(/2\.5000/)).toBeDefined();
    expect(await screen.findByRole('button', { name: 'Withdraw' })).toBeDefined();

    useDevelopmentFundSpy.mockRestore();
  });

  test.each([
    {
      missingInput: 'beneficiary',
      overrides: {
        beneficiary: '',
        isValid: false,
      },
    },
    {
      missingInput: 'amount',
      overrides: {
        amount: '',
        amountNum: null,
        isAmountValid: false,
        isValid: false,
      },
    },
    {
      missingInput: 'expiration date',
      overrides: {
        expiresAt: null,
        isExpiryValid: false,
        isValid: false,
      },
    },
    {
      missingInput: 'reason',
      overrides: {
        reason: '',
        isReasonValid: false,
        isValid: false,
      },
    },
  ])('disables Allocate button when $missingInput is missing', async ({ overrides }) => {
    const hookSpy = vi
      .spyOn(developmentFundAllocationFormHook, 'useDevelopmentFundAllocationForm')
      .mockReturnValue(buildAllocationFormMock(overrides));

    await loginAndOpenDevelopmentFund();

    expect(await screen.findByRole('button', { name: 'Allocate' })).toBeDisabled();

    hookSpy.mockRestore();
  });

  test.each([
    {
      invalidInput: 'amount',
      overrides: {
        amount: '0',
        amountNum: new BigNumber(0),
        isAmountValid: false,
        isValid: false,
      },
    },
    {
      invalidInput: 'amount above unclaimed total',
      overrides: {
        amount: '101',
        amountNum: new BigNumber(101),
        amountExceedsAvailable: true,
        isValid: false,
      },
    },
    {
      invalidInput: 'expiration date',
      overrides: {
        expiresAt: dayjs().subtract(1, 'day'),
        isExpiryValid: false,
        expiryError: 'Expiry must be in the future',
        isValid: false,
      },
    },
    {
      invalidInput: 'reason',
      overrides: {
        reason: '   ',
        isReasonValid: false,
        isValid: false,
      },
    },
  ])('disables Allocate button when $invalidInput is invalid', async ({ overrides }) => {
    const hookSpy = vi
      .spyOn(developmentFundAllocationFormHook, 'useDevelopmentFundAllocationForm')
      .mockReturnValue(buildAllocationFormMock(overrides));

    await loginAndOpenDevelopmentFund();

    expect(await screen.findByRole('button', { name: 'Allocate' })).toBeDisabled();

    hookSpy.mockRestore();
  });

  test('triggers allocation request on allocate click', async () => {
    const mutate = vi.fn();
    const expiresAt = dayjs().add(2, 'day');

    const hookSpy = vi
      .spyOn(developmentFundAllocationFormHook, 'useDevelopmentFundAllocationForm')
      .mockReturnValue({
        formKey: 0,
        error: null,
        beneficiary: alicePartyId,
        setBeneficiary: vi.fn(),
        amount: '1',
        setAmount: vi.fn(),
        expiresAt,
        setExpiresAt: vi.fn(),
        reason: 'Valid allocation',
        setReason: vi.fn(),
        amountNum: new BigNumber(1),
        isAmountValid: true,
        amountExceedsAvailable: false,
        isExpiryValid: true,
        expiryError: undefined,
        isReasonValid: true,
        isValid: true,
        resetForm: vi.fn(),
        allocateMutation: {
          mutate,
          isPending: false,
        } as unknown as ReturnType<
          typeof developmentFundAllocationFormHook.useDevelopmentFundAllocationForm
        >['allocateMutation'],
        isFundManager: true,
        unclaimedTotal: new BigNumber(100),
      });

    const { user } = await loginAndOpenDevelopmentFund();
    const allocateButton = await screen.findByRole('button', { name: 'Allocate' });
    await user.click(allocateButton);

    expect(mutate).toHaveBeenCalledWith({
      beneficiary: alicePartyId,
      amount: new BigNumber(1),
      expiresAt: expiresAt.toDate(),
      reason: 'Valid allocation',
    });

    hookSpy.mockRestore();
  });
});
