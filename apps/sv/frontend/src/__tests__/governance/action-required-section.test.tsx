// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  ActionRequiredSection,
  ActionRequiredData,
} from '../../components/governance/ActionRequiredSection';

const requests: ActionRequiredData[] = [
  {
    actionName: 'Feature Application',
    votingCloses: '2024-09-25 11:00',
    createdAt: '2024-09-25 11:00',
    requester: 'sv1',
  },
  {
    actionName: 'Set DSO Rules Configuration',
    votingCloses: '2024-09-25 11:00',
    createdAt: '2024-09-25 11:00',
    requester: 'sv2',
    isYou: true,
  },
];

describe('Action Required', () => {
  test('should render Action Required Section', async () => {
    render(<ActionRequiredSection actionRequiredRequests={requests} />);

    expect(await screen.findByText('Action Required')).toBeDefined();

    const badge = screen.getByTestId('action-required-badge-count');
    expect(badge).toBeDefined();
    expect(badge.textContent).toBe(`${requests.length}`);

    expect(true).toBe(true);
  });

  test('should render all action required requests', () => {
    render(<ActionRequiredSection actionRequiredRequests={requests} />);

    const cards = screen.getAllByTestId('action-required-card');
    expect(cards.length).toBe(requests.length);
  });

  test('should render action required request details', () => {
    const actionRequired = {
      actionName: 'Feature Application',
      votingCloses: '2029-09-25 11:00',
      createdAt: '2029-09-25 11:00',
      requester: 'sv1',
    };

    render(<ActionRequiredSection actionRequiredRequests={[actionRequired]} />);

    const action = screen.getByTestId('action-required-action');
    expect(action).toBeDefined();
    expect(action.textContent).toBe(actionRequired.actionName);

    const createdAt = screen.getByTestId('action-required-created-at');
    expect(createdAt).toBeDefined();
    expect(createdAt.textContent).toBe(actionRequired.createdAt);

    const votingCloses = screen.getByTestId('action-required-voting-closes');
    expect(votingCloses).toBeDefined();
    expect(votingCloses.textContent).toBe(actionRequired.votingCloses);

    const requester = screen.getByTestId('action-required-requester');
    expect(requester).toBeDefined();
    expect(requester.textContent).toBe(actionRequired.requester);

    const viewDetails = screen.getByTestId('action-required-view-details');
    expect(viewDetails).toBeDefined();
  });

  test('should render isYou badge for requests created by viewing sv', () => {
    const actionRequired = {
      actionName: 'Feature Application',
      votingCloses: '2029-09-25 11:00',
      createdAt: '2029-09-25 11:00',
      requester: 'sv1',
      isYou: true,
    };

    render(<ActionRequiredSection actionRequiredRequests={[actionRequired]} />);

    const isYou = screen.getByTestId('action-required-you');
    expect(isYou).toBeDefined();
  });

  test('should not render isYou badge for requests created by other svs', () => {
    const actionRequired = {
      actionName: 'Feature Application',
      votingCloses: '2029-09-25 11:00',
      createdAt: '2029-09-25 11:00',
      requester: 'sv1',
    };

    render(<ActionRequiredSection actionRequiredRequests={[actionRequired]} />);

    expect(() => screen.getByTestId('action-required-you')).toThrowError(
      /Unable to find an element/
    );
  });
});
