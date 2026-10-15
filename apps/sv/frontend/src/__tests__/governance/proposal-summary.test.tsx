// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { ProposalSummary } from '../../components/governance/ProposalSummary';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  EFFECTIVE_AT_LABEL,
  PROPOSAL_REVIEW_TITLE,
  SUPPORTING_URL_LABEL,
  THRESHOLD_DEADLINE_LABEL,
} from '../../utils/constants';
import { ConfigChange } from '../../utils/types';

const url = 'https://example.com';
const summary = 'Summary of the proposal';
const expiryDate = '2025-09-25 11:00';
const effectiveDate = '2025-09-26 11:00';
// vitest.global-setup.ts pins TZ=ETC/GMT-2 (UTC+2), so these render deterministically.
const expiryDateFormatted = '2025-09-25 11:00 (UTC+02:00)';
const effectiveDateFormatted = '2025-09-26 11:00 (UTC+02:00)';

/** Shared labels for the post-rebase ProposalSummary / ProposalReviewField chrome. */
const REVIEW_LABELS = {
  title: PROPOSAL_REVIEW_TITLE,
  action: CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  expiryDate: THRESHOLD_DEADLINE_LABEL,
  effectiveDate: EFFECTIVE_AT_LABEL,
  summary: 'Proposal Summary',
  url: SUPPORTING_URL_LABEL,
} as const;

function expectCommonReviewFields(actionName: string) {
  expect(screen.getByTestId('proposal-review-title').textContent).toBe(REVIEW_LABELS.title);
  expect(screen.getByTestId('action-title').textContent).toBe(REVIEW_LABELS.action);
  expect(screen.getByTestId('action-field').textContent).toBe(actionName);
  expect(screen.getByTestId('url-title').textContent).toBe(REVIEW_LABELS.url);
  expect(screen.getByTestId('url-field').textContent).toBe(url);
  expect(screen.getByTestId('summary-title').textContent).toBe(REVIEW_LABELS.summary);
  expect(screen.getByTestId('summary-field').textContent).toBe(summary);
  expect(screen.getByTestId('expiryDate-title').textContent).toBe(REVIEW_LABELS.expiryDate);
  expect(screen.getByTestId('expiryDate-field').textContent).toBe(expiryDateFormatted);
  expect(screen.getByTestId('effectiveDate-title').textContent).toBe(REVIEW_LABELS.effectiveDate);
}

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

    expectCommonReviewFields(actionName);
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDateFormatted);

    expect(screen.getByTestId('offboardMember-title').textContent).toBe('Member');
    expect(screen.getByTestId('offboardMember-party-id-value').textContent).toBe(offboardMember);
    expect(screen.getByTestId('offboardMember-party-id-copy-button')).toBeInTheDocument();
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

    expect(screen.getByTestId('effectiveDate-title').textContent).toBe(EFFECTIVE_AT_LABEL);
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe('Threshold');
  });

  test('should render review proposal component for sv reward weight', () => {
    const actionName = 'Update Super Validator Reward Weight';
    const title = 'Super Validator Reward Weight';
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

    expectCommonReviewFields(actionName);
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDateFormatted);

    expect(screen.getByTestId('svRewardWeightMember-title').textContent).toBe('Member');
    expect(screen.getByTestId('svRewardWeightMember-party-id-value').textContent).toBe(
      svRewardWeightMember
    );
    expect(screen.getByTestId('svRewardWeightMember-party-id-copy-button')).toBeInTheDocument();

    expect(screen.getByTestId('configChange-title').textContent).toBe('Proposed Changes');
    expect(screen.getByTestId('config-change-field-label').textContent).toBe(title);
    expect(screen.getByTestId('config-change-current-value').textContent).toBe(currentWeight);
    expect(screen.getByTestId('config-change-new-value').textContent).toBe(svRewardWeight);
  });

  test('should render review proposal component for feature application', () => {
    const actionName = 'Feature Application';
    const provider = 'Digital-Asset-Eng-2';
    const activityWeight = '2.5';

    render(
      <ProposalSummary
        actionName={actionName}
        url={url}
        summary={summary}
        expiryDate={expiryDate}
        effectiveDate={effectiveDate}
        formType="grant-right"
        grantRight={provider}
        activityWeight={activityWeight}
        onEdit={() => {}}
        onSubmit={() => {}}
      />
    );

    expectCommonReviewFields(actionName);
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDateFormatted);

    expect(screen.getByTestId('grantRight-title').textContent).toBe('Provider Party ID');
    expect(screen.getByTestId('grantRight-party-id-value').textContent).toBe(provider);
    expect(screen.getByTestId('grantRight-party-id-copy-button')).toBeInTheDocument();

    expect(screen.getByTestId('grantRightActivityWeight-title').textContent).toBe(
      'Activity Weight'
    );
    expect(screen.getByTestId('grantRightActivityWeight-field').textContent).toBe(activityWeight);
  });

  test('should render review proposal component for unfeature application', () => {
    const actionName = 'UnFeature Application';
    const providerPartyId = 'a-party-id::1014912492';
    const contractId = 'bcde123456';

    render(
      <ProposalSummary
        actionName={actionName}
        url={url}
        summary={summary}
        expiryDate={expiryDate}
        effectiveDate={effectiveDate}
        formType="revoke-right"
        providerPartyId={providerPartyId}
        revokeRight={contractId}
        onEdit={() => {}}
        onSubmit={() => {}}
      />
    );

    expectCommonReviewFields(actionName);
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDateFormatted);

    expect(screen.getByTestId('revokeProviderPartyId-title').textContent).toBe('Provider Party ID');
    expect(screen.getByTestId('revokeProviderPartyId-party-id-value').textContent).toBe(
      providerPartyId
    );
    expect(screen.getByTestId('revokeProviderPartyId-party-id-copy-button')).toBeInTheDocument();

    expect(screen.getByTestId('revokeRight-title').textContent).toBe(
      'Featured Application Contract ID'
    );
    expect(screen.getByTestId('revokeRight-field').textContent).toBe(contractId);
  });

  test('should render review proposal component for update feature application', () => {
    const actionName = 'Update Featured Application';
    const providerPartyId = 'a-party-id::1014912492';
    const rightCid = 'bcde123456';
    const currentActivityWeight = '1.0';
    const newActivityWeight = '2.5';

    render(
      <ProposalSummary
        actionName={actionName}
        url={url}
        summary={summary}
        expiryDate={expiryDate}
        effectiveDate={effectiveDate}
        formType="update-right-weight"
        providerPartyId={providerPartyId}
        rightCid={rightCid}
        currentActivityWeight={currentActivityWeight}
        newActivityWeight={newActivityWeight}
        onEdit={() => {}}
        onSubmit={() => {}}
      />
    );

    expectCommonReviewFields(actionName);
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDateFormatted);

    expect(screen.getByTestId('updateProviderPartyId-title').textContent).toBe('Provider Party ID');
    expect(screen.getByTestId('updateProviderPartyId-party-id-value').textContent).toBe(
      providerPartyId
    );
    expect(screen.getByTestId('updateProviderPartyId-party-id-copy-button')).toBeInTheDocument();

    expect(screen.getByTestId('updateRight-title').textContent).toBe(
      'Featured Application Contract ID'
    );
    expect(screen.getByTestId('updateRight-field').textContent).toBe(rightCid);

    expect(screen.getByTestId('updateActivityWeight-title').textContent).toBe('Proposed Changes');
    expect(screen.getByTestId('config-change-current-value').textContent).toBe(
      currentActivityWeight
    );
    expect(screen.getByTestId('config-change-new-value').textContent).toBe(newActivityWeight);

    expect(screen.queryByTestId('updateReason-field')).not.toBeInTheDocument();
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

    expectCommonReviewFields(actionName);
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDateFormatted);

    expect(screen.getByTestId('configChange-title').textContent).toBe(
      'Proposed Configuration Changes'
    );
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

    expectCommonReviewFields(actionName);
    expect(screen.getByTestId('effectiveDate-field').textContent).toBe(effectiveDateFormatted);

    expect(screen.getByTestId('configChange-title').textContent).toBe(
      'Proposed Configuration Changes'
    );
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
