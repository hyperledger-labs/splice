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
import { MemoryRouter } from 'react-router';
import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';

const requests: ActionRequiredData[] = [
  {
    actionName: 'Feature Application',
    description: 'Test description for feature application',
    contractId: '2abcde123456' as ContractId<VoteRequest>,
    votingCloses: '2024-09-25 11:00',
    createdAt: '2024-09-25 11:00',
    requester: 'sv1',
  },
  {
    actionName: 'Set DSO Rules Configuration',
    description: 'Test description for DSO rules configuration',
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

    expect(await screen.findByText('Action Required')).toBeInTheDocument();

    const badge = screen.getByTestId('action-required-badge-count');
    expect(badge).toBeInTheDocument();
    expect(badge.textContent).toBe(`${requests.length}`);

    expect(true).toBe(true);
  });

  test('should render no items message when no items available', () => {
    render(
      <MemoryRouter>
        <ActionRequiredSection actionRequiredRequests={[]} />
      </MemoryRouter>
    );

    expect(screen.getByText('No Action Required items available')).toBeInTheDocument();
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
    const createdDate = dayjs().format(dateTimeFormatISO);
    const closesDate = dayjs().add(10, 'days').format(dateTimeFormatISO);
    const actionRequired = {
      actionName: 'Feature Application',
      description: 'Test description',
      contractId: '2abcde123456' as ContractId<VoteRequest>,
      votingCloses: closesDate,
      createdAt: createdDate,
      requester: 'sv1',
    };

    render(
      <MemoryRouter>
        <ActionRequiredSection actionRequiredRequests={[actionRequired]} />
      </MemoryRouter>
    );

    const action = screen.getByTestId('action-required-action-content');
    expect(action).toBeInTheDocument();
    expect(action.textContent).toBe(actionRequired.actionName);

    const description = screen.getByTestId('action-required-description-content');
    expect(description).toBeInTheDocument();
    expect(description.textContent).toBe(actionRequired.description);

    const votingCloses = screen.getByTestId('action-required-voting-closes-content');
    expect(votingCloses).toBeInTheDocument();
    expect(votingCloses.textContent).toBe('10 days');

    const requester = screen.getByTestId('action-required-requester-identifier-value');
    expect(requester).toBeInTheDocument();
    expect(requester.textContent).toBe(actionRequired.requester);

    const viewDetails = screen.getByTestId('action-required-view-details');
    expect(viewDetails).toBeInTheDocument();
  });

  test('should render isYou badge for requests created by viewing sv', () => {
    const actionRequired = {
      actionName: 'Feature Application',
      description: 'Test description',
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

    const isYou = screen.getByTestId('action-required-requester-identifier-badge');
    expect(isYou).toBeInTheDocument();
  });

  test('should not render isYou badge for requests created by other svs', () => {
    const actionRequired = {
      actionName: 'Feature Application',
      description: 'Test description',
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

    const isYou = screen.queryByTestId('action-required-requester-identifier-badge');

    expect(isYou).not.toBeInTheDocument();
  });
});
