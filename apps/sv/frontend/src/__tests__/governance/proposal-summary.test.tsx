// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { ProposalSummary } from '../../components/governance/ProposalSummary';
import { ConfigChange } from '../../utils/types';

const url = 'https://example.com';
const summary = 'Summary of the proposal';
const expiryDate = '2025-09-25 11:00';
const effectiveDate = '2025-09-26 11:00';

describe('Review Proposal Component', () => {
  test('should render review proposal component for offboard member', () => {
    const actionName = 'Offboard Member';
    const offboardMember = 'Digital-Asset-Eng-2';

    render(
      <ProposalSummary
        actionName={actionName}
        url={url}
        summary={summary}
        expiryDate={expiryDate}
        effectiveDate={effectiveDate}
        formType="offboard"
        offboardMember={offboardMember}
        onEdit={() => {}}
        onSubmit={() => {}}
      />
    );

    expect(screen.getByTestId('action-title').textContent).toBe('Action');
    expect(screen.getByTestId('action-field').textContent).toBe(actionName);

    expect(screen.getByTestId('url-title').textContent).toBe('URL');
    expect(screen.getByTestId('url-field').textContent).toBe(url);

    expect(screen.getByTestId('summary-title').textContent).toBe('Summary');
    expect(screen.getByTestId('summary-field').textContent).toBe(summary);

    expect(screen.getByTestId('expiryDate-title').textContent).toBe('Expiry Date');
    expect(screen.getByTestId('expiryDate-field').textContent).toBe(expiryDate);

    expect(screen.getByTestId('effectiveDate-title').textContent).toBe('Effective Date');
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDate);

    expect(screen.getByTestId('offboardMember-title').textContent).toBe('Offboard Member');
    expect(screen.getByTestId('offboardMember-field').textContent).toBe(offboardMember);
  });

  test('should render review proposal component for offboard member at Threshold', () => {
    const actionName = 'Offboard Member';
    const offboardMember = 'Digital-Asset-Eng-2';

    render(
      <ProposalSummary
        actionName={actionName}
        url={url}
        summary={summary}
        expiryDate={expiryDate}
        effectiveDate={undefined}
        formType="offboard"
        offboardMember={offboardMember}
        onEdit={() => {}}
        onSubmit={() => {}}
      />
    );

    expect(screen.getByTestId('effectiveDate-title').textContent).toBe('Effective Date');
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe('Threshold');
  });

  test('should render review proposal component for sv reward weight', () => {
    const actionName = 'Update SV Reward Weight';
    const title = 'SV Reward Weight';
    const svRewardWeightMember = 'Digital-Asset-Eng-2';
    const currentWeight = '1000';
    const svRewardWeight = '99';

    render(
      <ProposalSummary
        actionName={actionName}
        url={url}
        summary={summary}
        expiryDate={expiryDate}
        effectiveDate={effectiveDate}
        formType="sv-reward-weight"
        svRewardWeightMember={svRewardWeightMember}
        currentWeight={currentWeight}
        svRewardWeight={svRewardWeight}
        onEdit={() => {}}
        onSubmit={() => {}}
      />
    );

    expect(screen.getByTestId('action-title').textContent).toBe('Action');
    expect(screen.getByTestId('action-field').textContent).toBe(actionName);

    expect(screen.getByTestId('url-title').textContent).toBe('URL');
    expect(screen.getByTestId('url-field').textContent).toBe(url);

    expect(screen.getByTestId('summary-title').textContent).toBe('Summary');
    expect(screen.getByTestId('summary-field').textContent).toBe(summary);

    expect(screen.getByTestId('expiryDate-title').textContent).toBe('Expiry Date');
    expect(screen.getByTestId('expiryDate-field').textContent).toBe(expiryDate);

    expect(screen.getByTestId('effectiveDate-title').textContent).toBe('Effective Date');
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDate);

    expect(screen.getByTestId('config-change-field-label').textContent).toBe(title);
    expect(screen.getByTestId('config-change-current-value').textContent).toBe(currentWeight);
    expect(screen.getByTestId('config-change-new-value').textContent).toBe(svRewardWeight);
  });

  test('should render review proposal component for feature application', () => {
    const actionName = 'Feature Application';
    const provider = 'Digital-Asset-Eng-2';

    render(
      <ProposalSummary
        actionName={actionName}
        url={url}
        summary={summary}
        expiryDate={expiryDate}
        effectiveDate={effectiveDate}
        formType="grant-right"
        grantRight={provider}
        onEdit={() => {}}
        onSubmit={() => {}}
      />
    );

    expect(screen.getByTestId('action-title').textContent).toBe('Action');
    expect(screen.getByTestId('action-field').textContent).toBe(actionName);

    expect(screen.getByTestId('url-title').textContent).toBe('URL');
    expect(screen.getByTestId('url-field').textContent).toBe(url);

    expect(screen.getByTestId('summary-title').textContent).toBe('Summary');
    expect(screen.getByTestId('summary-field').textContent).toBe(summary);

    expect(screen.getByTestId('expiryDate-title').textContent).toBe('Expiry Date');
    expect(screen.getByTestId('expiryDate-field').textContent).toBe(expiryDate);

    expect(screen.getByTestId('effectiveDate-title').textContent).toBe('Effective Date');
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDate);

    expect(screen.getByTestId('grantRight-title').textContent).toBe('Provider');
    expect(screen.getByTestId('grantRight-field').textContent).toBe(provider);
  });

  test('should render review proposal component for unfeature application', () => {
    const actionName = 'UnFeature Application';
    const contractId = 'bcde123456';

    render(
      <ProposalSummary
        actionName={actionName}
        url={url}
        summary={summary}
        expiryDate={expiryDate}
        effectiveDate={effectiveDate}
        formType="revoke-right"
        revokeRight={contractId}
        onEdit={() => {}}
        onSubmit={() => {}}
      />
    );

    expect(screen.getByTestId('action-title').textContent).toBe('Action');
    expect(screen.getByTestId('action-field').textContent).toBe(actionName);

    expect(screen.getByTestId('url-title').textContent).toBe('URL');
    expect(screen.getByTestId('url-field').textContent).toBe(url);

    expect(screen.getByTestId('summary-title').textContent).toBe('Summary');
    expect(screen.getByTestId('summary-field').textContent).toBe(summary);

    expect(screen.getByTestId('expiryDate-title').textContent).toBe('Expiry Date');
    expect(screen.getByTestId('expiryDate-field').textContent).toBe(expiryDate);

    expect(screen.getByTestId('effectiveDate-title').textContent).toBe('Effective Date');
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDate);

    expect(screen.getByTestId('revokeRight-title').textContent).toBe(
      'Featured Application Right Contract Id'
    );
    expect(screen.getByTestId('revokeRight-field').textContent).toBe(contractId);
  });

  test('should render review proposal component for dso rules config', () => {
    const actionName = 'Set DSO Rules Configuration';
    const numThresholdTitle = 'Number of Unclaimed Rewards Threshold';
    const voteCooldownTitle = 'Vote Cooldown Time';

    const configChanges: ConfigChange[] = [
      {
        label: numThresholdTitle,
        fieldName: 'numUnclaimedRewardsThreshold',
        currentValue: '11',
        newValue: '12',
      },
      {
        label: voteCooldownTitle,
        fieldName: 'voteCooldownTime',
        currentValue: '3600',
        newValue: '3601',
      },
    ];

    render(
      <ProposalSummary
        actionName={actionName}
        url={url}
        summary={summary}
        expiryDate={expiryDate}
        effectiveDate={effectiveDate}
        formType="config-change"
        configFormData={configChanges}
        onEdit={() => {}}
        onSubmit={() => {}}
      />
    );

    expect(screen.getByTestId('action-title').textContent).toBe('Action');
    expect(screen.getByTestId('action-field').textContent).toBe(actionName);

    expect(screen.getByTestId('url-title').textContent).toBe('URL');
    expect(screen.getByTestId('url-field').textContent).toBe(url);

    expect(screen.getByTestId('summary-title').textContent).toBe('Summary');
    expect(screen.getByTestId('summary-field').textContent).toBe(summary);

    expect(screen.getByTestId('expiryDate-title').textContent).toBe('Expiry Date');
    expect(screen.getByTestId('expiryDate-field').textContent).toBe(expiryDate);

    expect(screen.getByTestId('effectiveDate-title').textContent).toBe('Effective Date');
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDate);

    expect(screen.getByText('Proposed Changes')).toBeDefined();
    expect(screen.getByText(numThresholdTitle)).toBeDefined();
    expect(screen.getByText(voteCooldownTitle)).toBeDefined();

    const configChangeElements = screen.getAllByTestId('config-change');
    expect(configChangeElements.length).toBe(2);

    const withTitle = (title: string) =>
      configChangeElements.find(e => e.children.item(0)?.textContent?.includes(title));

    const numThresholdData = withTitle(numThresholdTitle)?.textContent;
    expect(numThresholdData).toBeDefined();
    expect(numThresholdData).toMatch(/Number of Unclaimed Rewards Threshold/);
    expect(numThresholdData).toMatch(/11/);
    expect(numThresholdData).toMatch(/12/);

    const voteCooldownData = withTitle(voteCooldownTitle)?.textContent;
    expect(voteCooldownData).toBeDefined();
    expect(voteCooldownData).toMatch(/Vote Cooldown Time/);
    expect(voteCooldownData).toMatch(/3600/);
    expect(voteCooldownData).toMatch(/3601/);
  });

  test('should render review proposal component for amulet rules config', () => {
    const actionName = 'Set Amulet Rules Configuration';
    const feeTitle = 'Transfer Preapproval Fee';
    const feeRateTitle = 'Transfer Config Transfer Fee Initial Rate';

    const configChanges: ConfigChange[] = [
      {
        label: feeTitle,
        fieldName: 'transferPreapprovalFee',
        currentValue: '99',
        newValue: '100',
      },
      {
        label: feeRateTitle,
        fieldName: 'transferConfigTransferFeeInitialRate',
        currentValue: '9.99',
        newValue: '10.99',
      },
    ];

    render(
      <ProposalSummary
        actionName={actionName}
        url={url}
        summary={summary}
        expiryDate={expiryDate}
        effectiveDate={effectiveDate}
        formType="config-change"
        configFormData={configChanges}
        onEdit={() => {}}
        onSubmit={() => {}}
      />
    );

    expect(screen.getByTestId('action-title').textContent).toBe('Action');
    expect(screen.getByTestId('action-field').textContent).toBe(actionName);

    expect(screen.getByTestId('url-title').textContent).toBe('URL');
    expect(screen.getByTestId('url-field').textContent).toBe(url);

    expect(screen.getByTestId('summary-title').textContent).toBe('Summary');
    expect(screen.getByTestId('summary-field').textContent).toBe(summary);

    expect(screen.getByTestId('expiryDate-title').textContent).toBe('Expiry Date');
    expect(screen.getByTestId('expiryDate-field').textContent).toBe(expiryDate);

    expect(screen.getByTestId('effectiveDate-title').textContent).toBe('Effective Date');
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDate);

    expect(screen.getByText('Proposed Changes')).toBeDefined();
    expect(screen.getByText(feeTitle)).toBeDefined();
    expect(screen.getByText(feeRateTitle)).toBeDefined();

    const configChangeElements = screen.getAllByTestId('config-change');
    expect(configChangeElements.length).toBe(2);

    const withTitle = (title: string) =>
      configChangeElements.find(e => e.children.item(0)?.textContent?.includes(title));

    const transferPreapprovalFeeData = withTitle(feeTitle)?.textContent;
    expect(transferPreapprovalFeeData).toBeDefined();
    expect(transferPreapprovalFeeData).toMatch(/Transfer Preapproval Fee/);
    expect(transferPreapprovalFeeData).toMatch(/99/);
    expect(transferPreapprovalFeeData).toMatch(/100/);

    const transferConfigTransferFeeInitialRateData = withTitle(feeRateTitle)?.textContent;
    expect(transferConfigTransferFeeInitialRateData).toBeDefined();
    expect(transferConfigTransferFeeInitialRateData).toMatch(
      /Transfer Config Transfer Fee Initial Rate/
    );
    expect(transferConfigTransferFeeInitialRateData).toMatch(/9.99/);
    expect(transferConfigTransferFeeInitialRateData).toMatch(/10.99/);
  });
});
