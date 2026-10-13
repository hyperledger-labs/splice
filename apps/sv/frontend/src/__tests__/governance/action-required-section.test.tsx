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
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { svPartyId, voteRequests } from '../mocks/constants';

const sampleContractId = voteRequests.dso_rules_vote_requests[0]
  .contract_id as ContractId<VoteRequest>;

const requests: ActionRequiredData[] = [
  {
    actionName: 'Feature Application',
    description: 'Test description for feature application',
    contractId: sampleContractId,
    votingCloses: '2024-09-25 11:00',
    createdAt: '2024-09-25 11:00',
    requester: svPartyId,
  },
  {
    actionName: 'Set DSO Rules Configuration',
    description: 'Test description for DSO rules configuration',
    contractId: voteRequests.dso_rules_vote_requests[1].contract_id as ContractId<VoteRequest>,
    votingCloses: '2024-09-25 11:00',
    createdAt: '2024-09-25 11:00',
    requester: svPartyId,
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
      contractId: sampleContractId,
      votingCloses: closesDate,
      createdAt: createdDate,
      requester: svPartyId,
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

    const createdAt = screen.getByTestId('action-required-created-at-content');
    expect(createdAt).toBeInTheDocument();
    expect(createdAt.textContent).toBe(`${actionRequired.createdAt} (UTC+02:00)`);

    const votingCloses = screen.getByTestId('action-required-voting-closes-content');
    expect(votingCloses).toBeInTheDocument();
    expect(votingCloses.textContent).toBe('10 days');

    const submittedBy = screen.getByTestId('action-required-submitted-by-identifier-value');
    expect(submittedBy).toBeInTheDocument();
    expect(submittedBy.textContent).toBe(svPartyId);

    const viewDetails = screen.getByTestId('action-required-view-details');
    expect(viewDetails).toBeInTheDocument();
  });

  test('should render submitted by with copy button and no You badge', () => {
    const actionRequired = {
      actionName: 'Feature Application',
      description: 'Test description',
      contractId: sampleContractId,
      votingCloses: '2029-09-25 11:00',
      createdAt: '2029-09-25 11:00',
      requester: svPartyId,
    };

    render(
      <MemoryRouter>
        <ActionRequiredSection actionRequiredRequests={[actionRequired]} />
      </MemoryRouter>
    );

    expect(
      screen.getByTestId('action-required-submitted-by-identifier-copy-button')
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId('action-required-submitted-by-identifier-badge')
    ).not.toBeInTheDocument();
  });

  test('should render vote proposal contract id with full value', () => {
    const actionRequired = {
      actionName: 'Feature Application',
      description: 'Test description',
      contractId: sampleContractId,
      votingCloses: '2029-09-25 11:00',
      createdAt: '2029-09-25 11:00',
      requester: svPartyId,
    };

    render(
      <MemoryRouter>
        <ActionRequiredSection actionRequiredRequests={[actionRequired]} />
      </MemoryRouter>
    );

    expect(screen.getByTestId('action-required-contract-id-value').textContent).toBe(
      sampleContractId
    );
  });
});
