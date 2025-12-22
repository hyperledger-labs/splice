// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { rest } from 'msw';
import { LookupTransferPreapprovalByPartyResponse } from '@lfdecentralizedtrust/scan-openapi';
import { test, expect, describe } from 'vitest';
import { vi } from 'vitest';

import App from '../App';
import { WalletConfigProvider } from '../utils/config';
import {
  aliceEntry,
  alicePartyId,
  aliceTransferPreapproval,
  bobPartyId,
  nameServiceEntries,
  userLogin,
} from './mocks/constants';
import {
  mockMintingDelegations,
  delegationExpiresAt,
} from './mocks/delegation-constants';
import { requestMocks } from './mocks/handlers/transfers-api';
import { server } from './setup/setup';
import {
  AllocateAmuletRequest,
  AllocateAmuletResponse,
  AmuletAllocationWithdrawResult,
  ChoiceExecutionMetadata,
  ListAllocationRequestsResponse,
  ListAllocationsResponse,
} from '@lfdecentralizedtrust/wallet-openapi';
import { AllocationRequest } from '@daml.js/splice-api-token-allocation-request/lib/Splice/Api/Token/AllocationRequestV1/module';
import { mkContract } from './mocks/contract';
import { openApiRequestFromTransferLeg } from '../components/ListAllocationRequests';
import * as damlTypes from '@daml/types';
import { ContractId } from '@daml/types';
import { AnyContract } from '@daml.js/splice-api-token-metadata/lib/Splice/Api/Token/MetadataV1/module';
import { AmuletAllocation } from '@daml.js/splice-amulet/lib/Splice/AmuletAllocation';

const dsoEntry = nameServiceEntries.find(e => e.name.startsWith('dso'))!;

const walletUrl = window.splice_config.services.validator.url;

function featureSupportHandler(
  tokenStandardSupported: boolean,
  transferPreapprovalDescriptionSupported: boolean
) {
  return rest.get(`${walletUrl}/v0/feature-support`, async (_, res, ctx) => {
    return res(
      ctx.json({
        token_standard: tokenStandardSupported,
        transfer_preapproval_description: transferPreapprovalDescriptionSupported,
      })
    );
  });
}

test('login screen shows up', async () => {
  render(
    <WalletConfigProvider>
      <App />
    </WalletConfigProvider>
  );
  expect(() => screen.findByText('Log In')).toBeDefined();
});

describe('Wallet user can', () => {
  test('login and see the user party ID', async () => {
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, userLogin);

    const button = screen.getByRole('button', { name: 'Log In' });
    user.click(button);

    expect(await screen.findByText(aliceEntry.name)).toBeDefined();
  });

  test('create a transfer preapproval', async () => {
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    const preapproveTransfersBtn = await screen.findByRole('button', {
      name: /Pre-approve incoming direct transfers/,
    });
    expect(preapproveTransfersBtn).toBeEnabled();
    await user.click(preapproveTransfersBtn);
    // Click in confirmation dialog
    const proceedBtn = await screen.findByRole('button', { name: 'Proceed' });
    await user.click(proceedBtn);
    // Check that clicking the button calls the correct backend endpoint
    expect(requestMocks.createTransferPreapproval).toHaveBeenCalled();
    // Mock the request to fetch the created pre-approval
    server.use(
      rest.get(
        `${window.splice_config.services.validator.url}/v0/scan-proxy/transfer-preapprovals/by-party/:party`,
        (req, res, ctx) => {
          const { party } = req.params;
          if (party === alicePartyId) {
            return res(
              ctx.json<LookupTransferPreapprovalByPartyResponse>({
                transfer_preapproval: aliceTransferPreapproval,
              })
            );
          } else {
            return res(ctx.status(404), ctx.json({}));
          }
        }
      )
    );
    const disabledPreapproveTransfersBtn = await screen.findByRole('button', {
      name: /Pre-approve incoming direct transfers/,
    });
    await waitFor(() => expect(disabledPreapproveTransfersBtn).toBeDisabled());
  });

  test('not see dso in list of transfer-offer receivers', async () => {
    server.use(featureSupportHandler(true, true));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    const transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    // the listbox is not visible, so we have to click the input
    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    await user.click(receiverInput);
    expect(screen.getByRole('listbox')).toBeDefined();

    const receiversListbox = screen.getByRole('listbox');
    const entries = within(receiversListbox)
      .getAllByRole('option')
      .map(e => e.textContent);

    expect(entries.length).toBeGreaterThan(1);
    expect(entries.find(e => e === dsoEntry.name)).toBeUndefined();
  });

  describe('Token Standard', () => {
    transferTests(false);

    test('fall back to non-token standard transfers when the token standard is not supported', async () => {
      server.use(featureSupportHandler(false, true));

      const user = userEvent.setup();
      render(
        <WalletConfigProvider>
          <App />
        </WalletConfigProvider>
      );
      expect(await screen.findByText('Transfer')).toBeDefined();

      const transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
      await user.click(transferOffersLink);
      expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

      const receiverInput = screen
        .getAllByRole('combobox')
        .find(e => e.id === 'create-offer-receiver')!;
      fireEvent.change(receiverInput, { target: { value: 'bob::nopreapproval' } });
      await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled(), {
        timeout: 2000,
      });
      expect(screen.queryByRole('checkbox', { name: '' })).not.toBeInTheDocument();
      expect(
        screen.queryByRole('switch', { name: 'Use Token Standard Transfer' })
      ).not.toBeInTheDocument();
      expect(screen.getByRole('textbox', { name: 'description' })).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Send' }));

      await assertCorrectMockIsCalled(
        true,
        { amount: '1.0', receiver_party_id: 'bob::nopreapproval', description: '' },
        false
      );
    });

    describe('Allocations', () => {
      test('see allocations', async () => {
        const allocations = Array.from({ length: 3 }, (_, i) =>
          getAllocation(
            `settlement_${i}`,
            `transfer_leg_${i}`,
            `receiver_${i}::party`,
            `${i + 1}`,
            'executor'
          )
        );
        server.use(
          rest.get(`${walletUrl}/v0/allocations`, (_req, res, ctx) => {
            return res(
              ctx.json<ListAllocationsResponse>({
                allocations: allocations.map(allocationPayload => {
                  return {
                    contract: mkContract(AmuletAllocation, allocationPayload),
                  };
                }),
              })
            );
          })
        );

        const user = userEvent.setup();
        render(
          <WalletConfigProvider>
            <App />
          </WalletConfigProvider>
        );
        expect(await screen.findByText('Allocations')).toBeDefined();

        const allocationsLink = screen.getByRole('link', { name: 'Allocations' });
        await user.click(allocationsLink);
        expect(
          screen.getByRole('heading', { name: `Allocations ${allocations.length}` })
        ).toBeDefined();

        const settlementRefIds = await screen.findAllByText(/SettlementRef id.*/);
        expect(settlementRefIds.map(e => e.textContent)).toStrictEqual(
          allocations.map(a => `SettlementRef id: ${a.allocation.settlement.settlementRef.id}`)
        );
      });

      test('see allocation requests, and accept them', async () => {
        const allocationRequest = getAllocationRequest();
        const allocationRequests = [allocationRequest];
        let calledCreate: (body: AllocateAmuletRequest) => void;
        const createPromise: Promise<AllocateAmuletRequest> = new Promise(
          resolve => (calledCreate = resolve)
        );
        server.use(
          rest.get(
            `${walletUrl}/v0/wallet/token-standard/allocation-requests`,
            (_req, res, ctx) => {
              return res(
                ctx.json<ListAllocationRequestsResponse>({
                  allocation_requests: allocationRequests.map(contract => {
                    return { contract: mkContract(AllocationRequest, contract) };
                  }),
                })
              );
            }
          ),
          rest.get(`${walletUrl}/v0/allocations`, (_req, res, ctx) => {
            return res(
              ctx.json<ListAllocationsResponse>({
                allocations: [],
              })
            );
          })
        );

        const user = userEvent.setup();
        render(
          <WalletConfigProvider>
            <App />
          </WalletConfigProvider>
        );
        expect(await screen.findByText('Allocations')).toBeDefined();

        const allocationsLink = screen.getByRole('link', { name: 'Allocations' });
        await user.click(allocationsLink);
        expect(
          screen.getByRole('heading', { name: `Allocation Requests ${allocationRequests.length}` })
        ).toBeDefined();

        expect(
          await screen.findByText(
            `SettlementRef id: ${allocationRequest.settlement.settlementRef.id}`
          )
        ).toBeDefined();

        const acceptButtons = await screen.findAllByRole('button', { name: 'Accept' });
        // one has a different sender, and one a different instrument, so those shouldn't be accepted
        expect(acceptButtons.length).toBe(1);

        server.use(
          rest.post(`${walletUrl}/v0/allocations`, async (req, res, ctx) => {
            const body = await req.json();
            calledCreate(body);
            const response: AllocateAmuletResponse = {
              output: {
                allocation_instruction_cid: 'alloc_instr_cid',
                allocation_cid: 'alloc_cid',
                dummy: {},
              },
              sender_change_cids: ['whatever'],
              meta: {},
            };
            return res(ctx.json(response));
          })
        );

        acceptButtons[0].click();
        const calledWithBody = await createPromise;
        const expected = openApiRequestFromTransferLeg(
          allocationRequest.settlement,
          allocationRequest.transferLegs.acceptable,
          'acceptable'
        );
        expect(calledWithBody).toStrictEqual(expected);
      });

      test("withdraw allocations from the allocation or the allocation request's leg views", async () => {
        const allocationRequestPayload = getAllocationRequest();
        const allocationRequest = mkContract(AllocationRequest, allocationRequestPayload);
        const allocationRequests = [allocationRequest];
        const allocation = mkContract(
          AmuletAllocation,
          getAllocation(
            allocationRequestPayload.settlement.settlementRef.id,
            'acceptable',
            allocationRequestPayload.transferLegs.acceptable.receiver,
            allocationRequestPayload.transferLegs.acceptable.amount,
            allocationRequestPayload.settlement.executor
          )
        );
        const allocations = [allocation];

        const calledWithdrawArgs: string[] = [];

        server.use(
          rest.get(
            `${walletUrl}/v0/wallet/token-standard/allocation-requests`,
            (_req, res, ctx) => {
              return res(
                ctx.json<ListAllocationRequestsResponse>({
                  allocation_requests: allocationRequests.map(contract => {
                    return { contract };
                  }),
                })
              );
            }
          ),
          rest.get(`${walletUrl}/v0/allocations`, (_req, res, ctx) => {
            return res(
              ctx.json<ListAllocationsResponse>({
                allocations: allocations.map(contract => {
                  return { contract };
                }),
              })
            );
          }),
          rest.post(`${walletUrl}/v0/allocations/:cid/withdraw`, (req, res, ctx) => {
            calledWithdrawArgs.push(req.params.cid.toString());
            return res(
              ctx.json<AmuletAllocationWithdrawResult>({
                sender_holding_cids: [],
                meta: {},
              })
            );
          })
        );

        const user = userEvent.setup();
        render(
          <WalletConfigProvider>
            <App />
          </WalletConfigProvider>
        );
        expect(await screen.findByText('Allocations')).toBeDefined();
        const allocationsLink = screen.getByRole('link', { name: 'Allocations' });
        await user.click(allocationsLink);

        // there should be one allocation request and one allocation,
        // both of which with a withdraw button
        expect(
          await screen.findByLabelText(`Allocation Requests ${allocationRequests.length}`)
        ).toBeDefined();
        expect(await screen.findByLabelText(`Allocations ${allocations.length}`)).toBeDefined();

        const withdrawButtons = await screen.findAllByRole('button', { name: 'Withdraw' });
        expect(withdrawButtons).to.have.length(2);

        for (const button of withdrawButtons) {
          await user.click(button);
        }

        expect(calledWithdrawArgs).toStrictEqual([allocation.contract_id, allocation.contract_id]);
      });

      test('reject allocation requests', async () => {
        const allocationRequestPayload = getAllocationRequest();
        const allocationRequest = mkContract(AllocationRequest, allocationRequestPayload);
        const allocationRequests = [allocationRequest];

        const calledRejectArgs: string[] = [];

        server.use(
          rest.get(
            `${walletUrl}/v0/wallet/token-standard/allocation-requests`,
            (_req, res, ctx) => {
              return res(
                ctx.json<ListAllocationRequestsResponse>({
                  allocation_requests: allocationRequests.map(contract => {
                    return { contract };
                  }),
                })
              );
            }
          ),
          rest.get(`${walletUrl}/v0/allocations`, (_req, res, ctx) => {
            return res(
              ctx.json<ListAllocationsResponse>({
                allocations: [],
              })
            );
          }),
          rest.post(
            `${walletUrl}/v0/wallet/token-standard/allocation-requests/:cid/reject`,
            (req, res, ctx) => {
              calledRejectArgs.push(req.params.cid.toString());
              return res(
                ctx.json<ChoiceExecutionMetadata>({
                  meta: {},
                })
              );
            }
          )
        );

        const user = userEvent.setup();
        render(
          <WalletConfigProvider>
            <App />
          </WalletConfigProvider>
        );
        expect(await screen.findByText('Allocations')).toBeDefined();
        const allocationsLink = screen.getByRole('link', { name: 'Allocations' });
        await user.click(allocationsLink);

        // there should be one allocation request with a reject button
        expect(
          await screen.findByLabelText(`Allocation Requests ${allocationRequests.length}`)
        ).toBeDefined();

        const rejectButton = await screen.findByRole('button', { name: 'Reject' });
        await user.click(rejectButton);

        expect(calledRejectArgs).toStrictEqual([allocationRequest.contract_id]);
      });
    });
  });

  describe('Regular transfer offer', () => {
    transferTests(true);
  });

  test('see two-step transfers in transaction history', async () => {
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );

    expect(await screen.findByText('Transactions')).toBeDefined();

    const transactionsLink = screen.getByRole('link', { name: 'Transactions' });
    await user.click(transactionsLink);

    await vi.waitFor(() => expect(screen.findByText('Transaction History')).toBeDefined());

    expect(await screen.findByText('(Transfer offer 009a97ffdf… accepted)')).toBeDefined();
    expect(
      await screen.findByText(
        '(Transfer offer 009a97ffdf… for 10 to bob__wallet__user::12201d5aa7…: test transfer)'
      )
    ).toBeDefined();
    expect(
      await screen.findByText(
        '(Transfer offer 009a97ffdf… for 10 from bob__wallet__user::12201d5aa7…: test transfer)'
      )
    ).toBeDefined();
    expect(await screen.findByText('(Transfer offer 009a97ffdf… withdrawn)')).toBeDefined();
    // The withdraw has a dummy conversion rate of 0 so no amulet conversion rate is displayed
    expect(await screen.findAllByText('@')).toHaveLength(3);
  });

  test('navigate to delegations tab and see delegations table', async () => {
    server.use(featureSupportHandler(true, true));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );

    expect(await screen.findByText('Delegations')).toBeDefined();

    const delegationsLink = screen.getByRole('link', { name: 'Delegations' });
    await user.click(delegationsLink);

    // Wait for the delegations content to load
    expect(await screen.findByRole('heading', { name: 'Active' })).toBeDefined();

    // Now verify the table exists with correct structure
    expect(screen.getByRole('table', { name: 'delegations table' })).toBeDefined();

    // Verify table headers
    expect(screen.getByRole('columnheader', { name: 'Beneficiary' })).toBeDefined();
    expect(screen.getByRole('columnheader', { name: 'Max Amulets' })).toBeDefined();
    expect(screen.getByRole('columnheader', { name: 'Expiration' })).toBeDefined();
    expect(screen.getByRole('columnheader', { name: 'Actions' })).toBeDefined();

    // Verify all delegation rows are rendered
    const delegationRows = document.querySelectorAll('.delegation-row');
    expect(delegationRows.length).toBe(mockMintingDelegations.length);

    // Verify beneficiary values are displayed
    const beneficiaries = document.querySelectorAll('.delegation-beneficiary');
    expect(beneficiaries.length).toBe(mockMintingDelegations.length);
    mockMintingDelegations.forEach((delegation, index) => {
      expect(beneficiaries[index].textContent).toBe(delegation.beneficiary);
    });

    // Verify max amulets values are displayed
    const maxAmulets = document.querySelectorAll('.delegation-max-amulets');
    expect(maxAmulets.length).toBe(mockMintingDelegations.length);
    mockMintingDelegations.forEach((delegation, index) => {
      expect(maxAmulets[index].textContent).toBe(delegation.amuletMergeLimit);
    });

    // Verify expiration values are displayed (all same date in 2050)
    const expirations = document.querySelectorAll('.delegation-expiration');
    expect(expirations.length).toBe(mockMintingDelegations.length);
    expirations.forEach(expiration => {
      expect(expiration.textContent).toBe(delegationExpiresAt);
    });

    // Verify withdraw buttons are present for each delegation
    const withdrawButtons = document.querySelectorAll('.delegation-withdraw');
    expect(withdrawButtons.length).toBe(mockMintingDelegations.length);
    withdrawButtons.forEach(button => {
      expect(button.textContent).toBe('Withdraw');
    });
  });

  test('navigate to delegations tab and see empty state when no delegations', async () => {
    server.use(featureSupportHandler(true, true));
    // Override the minting-delegations endpoint to return empty list
    server.use(
      rest.get(
        `${walletUrl}/v0/wallet/minting-delegations`,
        (_, res, ctx) => {
          return res(ctx.json({ delegations: [] }));
        }
      )
    );

    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );

    expect(await screen.findByText('Delegations')).toBeDefined();

    const delegationsLink = screen.getByRole('link', { name: 'Delegations' });
    await user.click(delegationsLink);

    // Verify the heading is present
    expect(screen.getByRole('heading', { name: 'Active' })).toBeDefined();

    // Verify the "No delegations" message is displayed
    expect(await screen.findByText('None active')).toBeDefined();

    // Verify the table is NOT rendered
    expect(screen.queryByRole('table', { name: 'delegations table' })).toBeNull();
  });

  test('transfer preapproval (without token standard) does not show nor send description if not supported', async () => {
    // token standard as not supported
    server.use(featureSupportHandler(false, false));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    const transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::preapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled(), {
      timeout: 2000,
    });

    // there should be no description input
    expect(screen.queryByRole('textbox', { name: 'description' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Send' }));

    await assertCorrectMockIsCalled(
      true,
      {
        amount: '1.0',
        receiver_party_id: 'bob::preapproval',
        // description omitted: it is not sent
      },
      true
    );
  });

  test('transfer offers have description field when unsupported for preapprovals', async () => {
    // transfer preapprovals do not support description, but that's inconsequential to regular transfer offers
    server.use(featureSupportHandler(false, false));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    const transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::preapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled(), {
      timeout: 2000,
    });

    await user.click(screen.getByRole('checkbox'));

    const description = 'Works';
    const descriptionInput = screen.getByRole('textbox', { name: 'description' });
    await user.type(descriptionInput, description);

    await user.click(screen.getByRole('button', { name: 'Send' }));

    await assertCorrectMockIsCalled(
      true,
      {
        amount: '1.0',
        receiver_party_id: 'bob::preapproval',
        description,
      },
      false
    );
  });

  test('transfer offers have description field when party has no preapproval and transfer preapprovals do not support descriptions', async () => {
    // transfer preapprovals do not support description, but that's inconsequential to regular transfer offers
    server.use(featureSupportHandler(false, false));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    const transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::nopreapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled(), {
      timeout: 2000,
    });

    const description = 'Works';
    const descriptionInput = screen.getByRole('textbox', { name: 'description' });
    await user.type(descriptionInput, description);

    await user.click(screen.getByRole('button', { name: 'Send' }));

    await assertCorrectMockIsCalled(
      true,
      {
        amount: '1.0',
        receiver_party_id: 'bob::nopreapproval',
        description,
      },
      false
    );
  });
}, 7500);

function transferTests(disableTokenStandard: boolean) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  async function toggleTokenStandard(user: any): Promise<void> {
    if (disableTokenStandard) {
      await user.click(screen.getByRole('switch', { name: 'Use Token Standard Transfer' }));
    }
  }

  test('transfer offer is used when receiver has no transfer preapproval', async () => {
    server.use(featureSupportHandler(true, true));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    const transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::nopreapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled(), {
      timeout: 2000,
    });
    expect(screen.queryByRole('checkbox', { name: '' })).not.toBeInTheDocument();
    await toggleTokenStandard(user);
    const description = 'Test';
    const descriptionInput = screen.getByRole('textbox', { name: 'description' });
    await user.type(descriptionInput, description);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    await assertCorrectMockIsCalled(
      disableTokenStandard,
      { amount: '1.0', receiver_party_id: 'bob::nopreapproval', description },
      false
    );
  });

  test('transfer preapproval is used when receiver has a transfer preapproval', async () => {
    server.use(featureSupportHandler(true, true));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    const transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::preapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled(), {
      timeout: 2000,
    });
    // Checkbox is there, we don't change it though as the default uses the preapproval
    expect(screen.getByRole('checkbox', { name: '' })).toBeInTheDocument();
    await toggleTokenStandard(user);
    const description = 'Pre';
    const descriptionInput = screen.getByRole('textbox', { name: 'description' });
    await user.type(descriptionInput, description);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    await assertCorrectMockIsCalled(
      disableTokenStandard,
      { amount: '1.0', receiver_party_id: 'bob::preapproval', description },
      true
    );
  });

  test('transfer offer is used when receiver has a transfer preapproval but checkbox is unchecked', async () => {
    server.use(featureSupportHandler(true, true));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    const transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::preapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled(), {
      timeout: 2000,
    });
    expect(screen.getByRole('checkbox', { name: '' })).toBeInTheDocument();
    await toggleTokenStandard(user);
    await user.click(screen.getByRole('checkbox', { name: '' }));
    const description = 'Pre2';
    const descriptionInput = screen.getByRole('textbox', { name: 'description' });
    await user.type(descriptionInput, description);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    await assertCorrectMockIsCalled(
      disableTokenStandard,
      { amount: '1.0', receiver_party_id: 'bob::preapproval', description },
      false
    );
  });

  test('deduplication id is passed', async () => {
    server.use(featureSupportHandler(true, true));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    let transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    let receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::preapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled(), {
      timeout: 2000,
    });
    const mock = disableTokenStandard
      ? requestMocks.transferPreapprovalSend
      : requestMocks.createTransferViaTokenStandard;
    mock.mockImplementationOnce(() => {
      throw new Error('Request failed');
    });
    await toggleTokenStandard(user);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(mock).toHaveBeenCalledTimes(1);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    function getDeduplicationIdFromCall(call: any) {
      return call.deduplication_id || call.tracking_id;
    }
    const firstDeduplicationId = getDeduplicationIdFromCall(mock.mock.lastCall![0]);
    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(mock).toHaveBeenCalledTimes(2);
    const secondDeduplicationId = getDeduplicationIdFromCall(mock.mock.lastCall![0]);
    expect(firstDeduplicationId).toBe(secondDeduplicationId);

    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    receiverInput = screen.getAllByRole('combobox').find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::preapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled(), {
      timeout: 2000,
    });
    mock.mockImplementationOnce(() => {
      throw new Error('Request failed');
    });
    await toggleTokenStandard(user);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(mock).toHaveBeenCalledTimes(3);
    const thirdDeduplicationId = getDeduplicationIdFromCall(mock.mock.lastCall![0]);
    expect(thirdDeduplicationId).not.toBe(firstDeduplicationId);
  }, 15000);
}

async function assertCorrectMockIsCalled(
  usesRegularTransferOffer: boolean,
  expected: { amount: string; receiver_party_id: string; description?: string },
  isPreapproval: boolean
) {
  if (!usesRegularTransferOffer) {
    expect(requestMocks.createTransferViaTokenStandard).toHaveBeenCalledWith(
      expect.objectContaining(expected)
    );
    expect(requestMocks.transferPreapprovalSend).not.toHaveBeenCalled();
    expect(requestMocks.createTransferOffer).not.toHaveBeenCalled();
  } else if (isPreapproval) {
    expect(requestMocks.transferPreapprovalSend).toHaveBeenCalledWith(
      expect.objectContaining(expected)
    );
    // unfortunately 'objectContaining' does not work for `description: undefined`:
    // the mock omits the field and the expected has it as undefined.
    // Omitting `description` from `expected` doesn't work because then it's not checked at all.
    expect(requestMocks.transferPreapprovalSend.mock.lastCall![0].description).equals(
      expected.description
    );
    expect(requestMocks.createTransferOffer).not.toHaveBeenCalled();
    expect(requestMocks.createTransferViaTokenStandard).not.toHaveBeenCalled();
  } else {
    expect(requestMocks.createTransferOffer).toHaveBeenCalledWith(
      expect.objectContaining(expected)
    );
    expect(requestMocks.transferPreapprovalSend).not.toHaveBeenCalled();
    expect(requestMocks.createTransferViaTokenStandard).not.toHaveBeenCalled();
  }
}

function getAllocationRequest() {
  return {
    settlement: {
      executor: 'executor',
      settlementRef: {
        id: 'the_id',
        cid: null as damlTypes.Optional<ContractId<AnyContract>>,
      },
      requestedAt: new Date().toISOString(),
      allocateBefore: new Date().toISOString(),
      settleBefore: new Date().toISOString(),
      meta: { values: {} },
    },
    transferLegs: {
      acceptable: {
        sender: alicePartyId,
        receiver: bobPartyId,
        amount: '3',
        instrumentId: {
          id: 'Amulet',
          admin: 'dso::party',
        },
        meta: { values: {} },
      },
      different_sender: {
        sender: bobPartyId,
        receiver: alicePartyId,
        amount: '3',
        instrumentId: {
          id: 'Amulet',
          admin: 'dso::party',
        },
        meta: { values: {} },
      },
      different_instrument: {
        sender: alicePartyId,
        receiver: bobPartyId,
        amount: '3',
        instrumentId: {
          id: 'Another',
          admin: 'dso::party',
        },
        meta: { values: {} },
      },
    },
    meta: { values: {} },
  };
}

function getAllocation(
  settlementId: string,
  transferLegId: string,
  receiver: string,
  amount: string,
  executor: string
) {
  return {
    lockedAmulet: `lockedamulet${settlementId}`,
    allocation: {
      transferLegId,
      transferLeg: {
        sender: alicePartyId,
        receiver,
        amount,
        meta: { values: {} },
        instrumentId: { id: 'Amulet', admin: 'dso::party' },
      },
      settlement: {
        executor,
        settlementRef: {
          id: settlementId,
          cid: null,
        },
        requestedAt: new Date().toISOString(),
        allocateBefore: new Date().toISOString(),
        settleBefore: new Date().toISOString(),
        meta: { values: {} },
      },
    },
  };
}
