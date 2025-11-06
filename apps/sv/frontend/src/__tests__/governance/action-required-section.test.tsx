// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  ActionRequiredSection,
  ActionRequiredData,
} from '../../components/governance/ActionRequiredSection';
import { ContractId } from '@daml/types';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { MemoryRouter } from 'react-router-dom';

const requests: ActionRequiredData[] = [
  {
    actionName: 'Feature Application',
    contractId: '2abcde123456' as ContractId<VoteRequest>,
    votingCloses: '2024-09-25 11:00',
    createdAt: '2024-09-25 11:00',
    requester: 'sv1',
  },
  {
    actionName: 'Set DSO Rules Configuration',
    contractId: '2bcde123456' as ContractId<VoteRequest>,
    votingCloses: '2024-09-25 11:00',
    createdAt: '2024-09-25 11:00',
    requester: 'sv2',
    isYou: true,
  },
];

describe('Action Required', () => {
  test('should render Action Required Section', async () => {
    render(
      <MemoryRouter>
        <ActionRequiredSection actionRequiredRequests={requests} />
      </MemoryRouter>
    );

    expect(await screen.findByText('Action Required')).toBeDefined();

    const badge = screen.getByTestId('action-required-badge-count');
    expect(badge).toBeDefined();
    expect(badge.textContent).toBe(`${requests.length}`);

    expect(true).toBe(true);
  });

  test('should render no items message when no items available', () => {
    render(
      <MemoryRouter>
        <ActionRequiredSection actionRequiredRequests={[]} />
      </MemoryRouter>
    );

    expect(screen.getByText('No Action Required items available')).toBeDefined();
  });

  test('should render all action required requests', () => {
    render(
      <MemoryRouter>
        <ActionRequiredSection actionRequiredRequests={requests} />
      </MemoryRouter>
    );

    const cards = screen.getAllByTestId('action-required-card');
    expect(cards.length).toBe(requests.length);
  });

  test('should render action required request details', () => {
    const actionRequired = {
      actionName: 'Feature Application',
      contractId: '2abcde123456' as ContractId<VoteRequest>,
      votingCloses: '2029-09-25 11:00',
      createdAt: '2029-09-25 11:00',
      requester: 'sv1',
    };

    render(
      <MemoryRouter>
        <ActionRequiredSection actionRequiredRequests={[actionRequired]} />
      </MemoryRouter>
    );

    const action = screen.getByTestId('action-required-action-content');
    expect(action).toBeDefined();
    expect(action.textContent).toBe(actionRequired.actionName);

    const createdAt = screen.getByTestId('action-required-created-at-content');
    expect(createdAt).toBeDefined();
    expect(createdAt.textContent).toBe(actionRequired.createdAt);

    const votingCloses = screen.getByTestId('action-required-voting-closes-content');
    expect(votingCloses).toBeDefined();
    expect(votingCloses.textContent).toBe(actionRequired.votingCloses);

    const requester = screen.getByTestId('action-required-requester-identifier-party-id');
    expect(requester).toBeDefined();
    expect(requester.textContent).toBe(actionRequired.requester);

    const viewDetails = screen.getByTestId('action-required-view-details');
    expect(viewDetails).toBeDefined();
  });

  test('should render isYou badge for requests created by viewing sv', () => {
    const actionRequired = {
      actionName: 'Feature Application',
      contractId: '2abcde123456' as ContractId<VoteRequest>,
      votingCloses: '2029-09-25 11:00',
      createdAt: '2029-09-25 11:00',
      requester: 'sv1',
      isYou: true,
    };

    render(
      <MemoryRouter>
        <ActionRequiredSection actionRequiredRequests={[actionRequired]} />
      </MemoryRouter>
    );

    const isYou = screen.getByTestId('action-required-requester-identifier-you');
    expect(isYou).toBeDefined();
  });

  test('should not render isYou badge for requests created by other svs', () => {
    const actionRequired = {
      actionName: 'Feature Application',
      contractId: '2abcde123456' as ContractId<VoteRequest>,
      votingCloses: '2029-09-25 11:00',
      createdAt: '2029-09-25 11:00',
      requester: 'sv1',
    };

    render(
      <MemoryRouter>
        <ActionRequiredSection actionRequiredRequests={[actionRequired]} />
      </MemoryRouter>
    );

    expect(() => screen.getByTestId('action-required-requester-identifier-you')).toThrowError(
      /Unable to find an element/
    );
  });
});
