// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  ProposalDetailsContent,
  ProposalDetailsContentProps,
} from '../../components/governance/ProposalDetailsContent';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { MemoryRouter } from 'react-router-dom';
import { ThemeProvider } from '@mui/material';
import { theme } from '@lfdecentralizedtrust/splice-common-frontend';
import { ProposalDetails, ProposalVote, ProposalVotingInformation } from '../../utils/types';
import userEvent from '@testing-library/user-event';

const voteRequest = {
  contractId: 'abc123' as ContractId<VoteRequest>,
  proposalDetails: {
    actionName: 'Offboard Member',
    createdAt: '2025-01-01 13:00',
    url: 'https://example.com',
    summary: 'Summary of the proposal',
    isVoteRequest: true,
    action: 'SRARC_OffboardSv',
    proposal: {
      memberToOffboard: 'sv2',
    },
  },
  votingInformation: {
    requester: 'sv1',
    requesterIsYou: true,
    votingCloses: '2029-01-01 13:00',
    voteTakesEffect: '2029-01-02 13:00',
    status: 'Accepted',
  },
  votes: [
    {
      sv: 'sv1',
      isYou: true,
      vote: 'accepted',
      reason: {
        url: 'https://example.com',
        body: 'Reason',
      },
    },
    {
      sv: 'sv3',
      vote: 'rejected',
      reason: {
        url: 'https://example.com',
        body: 'Reason',
      },
    },
  ],
} as ProposalDetailsContentProps;

const voteResult = {
  contractId: 'abc123' as ContractId<VoteRequest>,
  proposalDetails: {
    actionName: 'Offboard Member',
    createdAt: '2024-01-01 13:00',
    url: 'https://example.com',
    summary: 'Summary of the proposal',
    isVoteRequest: true,
    action: 'SRARC_OffboardSv',
    proposal: {
      memberToOffboard: 'sv2',
    },
  },
  votingInformation: {
    requester: 'sv1',
    requesterIsYou: true,
    votingCloses: '2024-02-01 13:00',
    voteTakesEffect: '2024-02-02 13:00',
    status: 'Accepted',
  },
  votes: [
    {
      sv: 'sv1',
      isYou: true,
      vote: 'accepted',
      reason: {
        url: 'https://example.com',
        body: 'Reason',
      },
    },
    {
      sv: 'sv3',
      vote: 'rejected',
      reason: {
        url: 'https://example.com',
        body: 'Reason',
      },
    },
  ],
} as ProposalDetailsContentProps;

const Wrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  return (
    <ThemeProvider theme={theme}>
      <MemoryRouter>{children}</MemoryRouter>
    </ThemeProvider>
  );
};

describe('Proposal Details Content', () => {
  test('should render proposal details page', async () => {
    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={voteRequest.votingInformation}
          votes={voteRequest.votes}
        />
      </Wrapper>
    );

    const pageTitle = screen.getByTestId('proposal-details-title');
    expect(pageTitle.textContent).toMatch(/Proposal Details/);

    const action = screen.getByTestId('proposal-details-action-value');
    expect(action.textContent).toMatch(/Offboard Member/);

    const offboardSection = screen.getByTestId('proposal-details-offboard-member-section');
    expect(offboardSection).toBeDefined();

    const memberInput = within(offboardSection).getByTestId(
      'proposal-details-member-party-id-input'
    );
    expect(memberInput).toBeDefined();
    expect(memberInput.getAttribute('value')).toBe('sv2');

    const summary = screen.getByTestId('proposal-details-summary-value');
    expect(summary.textContent).toMatch(/Summary of the proposal/);

    const url = screen.getByTestId('proposal-details-url-value');
    expect(url.textContent).toMatch(/https:\/\/example.com/);

    const votingInformationSection = screen.getByTestId('proposal-details-voting-information');
    expect(votingInformationSection).toBeDefined();

    const requesterInput = within(votingInformationSection).getByTestId(
      'proposal-details-requester-party-id-input'
    );
    expect(requesterInput).toBeDefined();
    expect(requesterInput.getAttribute('value')).toBe('sv1');

    const votingClosesIso = within(votingInformationSection).getByTestId(
      'proposal-details-voting-closes-value'
    );
    expect(votingClosesIso.textContent).toBe('2029-01-01 13:00');

    const voteTakesEffectIso = within(votingInformationSection).getByTestId(
      'proposal-details-vote-takes-effect-value'
    );
    expect(voteTakesEffectIso.textContent).toBe('2029-01-02 13:00');

    const status = screen.getByTestId('proposal-details-status-value');
    expect(status.textContent).toMatch(/Accepted/);

    const votesSection = screen.getByTestId('proposal-details-votes');
    expect(votesSection).toBeDefined();

    const votes = within(votesSection).getAllByTestId('proposal-details-vote');
    expect(votes.length).toBe(2);

    expect(screen.getByTestId('proposal-details-your-vote-section')).toBeDefined();
    expect(screen.getByTestId('proposal-details-your-vote-input')).toBeDefined();
    expect(screen.getByTestId('proposal-details-your-vote-accept')).toBeDefined();
    expect(screen.getByTestId('proposal-details-your-vote-reject')).toBeDefined();
  });

  test('should render featured app proposal details', () => {
    const featuredAppDetails = {
      actionName: 'Feature App',
      action: 'SRARC_GrantFeaturedAppRight',
      proposal: {
        provider: 'provider',
      },
    } as ProposalDetails;

    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={featuredAppDetails}
          votingInformation={voteRequest.votingInformation}
          votes={voteRequest.votes}
        />
      </Wrapper>
    );

    const action = screen.getByTestId('proposal-details-action-value');
    expect(action.textContent).toMatch(/Feature App/);

    const featuredAppSection = screen.getByTestId('proposal-details-feature-app-section');
    expect(featuredAppSection).toBeDefined();

    const provider = screen.getByTestId('proposal-details-feature-app-label');
    expect(provider.textContent).toMatch(/Provider ID/);

    const providerValue = screen.getByTestId('proposal-details-feature-app-value');
    expect(providerValue.textContent).toMatch(/provider/);
  });

  test('should render unfeatured app proposal details', () => {
    const unfeaturedAppDetails = {
      actionName: 'Unfeature App',
      action: 'SRARC_RevokeFeaturedAppRight',
      proposal: {
        rightContractId: 'rightContractId',
      },
    } as ProposalDetails;

    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={unfeaturedAppDetails}
          votingInformation={voteRequest.votingInformation}
          votes={voteRequest.votes}
        />
      </Wrapper>
    );

    const action = screen.getByTestId('proposal-details-action-value');
    expect(action.textContent).toMatch(/Unfeature App/);

    const unfeaturedAppSection = screen.getByTestId('proposal-details-unfeature-app-section');
    expect(unfeaturedAppSection).toBeDefined();

    const rightContractId = screen.getByTestId('proposal-details-unfeature-app-label');
    expect(rightContractId.textContent).toMatch(/Contract ID/);

    const rightContractIdValue = screen.getByTestId('proposal-details-unfeature-app-value');
    expect(rightContractIdValue.textContent).toMatch(/rightContractId/);
  });

  test('should render update sv reward weight proposal details', () => {
    const updateSvRewardWeightDetails = {
      actionName: 'Update SV Reward Weight',
      action: 'SRARC_UpdateSvRewardWeight',
      proposal: {
        svToUpdate: 'sv2',
        weightChange: '1000',
      },
    } as ProposalDetails;

    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={updateSvRewardWeightDetails}
          votingInformation={voteRequest.votingInformation}
          votes={voteRequest.votes}
        />
      </Wrapper>
    );

    const action = screen.getByTestId('proposal-details-action-value');
    expect(action.textContent).toMatch(/Update SV Reward Weight/);

    const updateSvRewardWeightSection = screen.getByTestId(
      'proposal-details-update-sv-reward-weight-section'
    );
    expect(updateSvRewardWeightSection).toBeDefined();

    const svToUpdate = within(updateSvRewardWeightSection).getByTestId(
      'proposal-details-member-party-id-input'
    );
    expect(svToUpdate.getAttribute('value')).toBe('sv2');

    const weightChange = screen.getByTestId('proposal-details-weight-value');
    expect(weightChange.textContent).toBe('1000');
  });

  test('should render amulet rules config proposal details', () => {
    const amuletRulesConfigDetails = {
      actionName: 'Set Amulet Rules Config',
      action: 'CRARC_SetConfig',
      proposal: {
        configChanges: [
          {
            fieldName: 'Transfer (Create Fee)',
            currentValue: '0.03',
            newValue: '0.04',
          },
          {
            fieldName: 'Max Num Inputs',
            currentValue: '3',
            newValue: '4',
          },
        ],
      },
    } as ProposalDetails;

    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={amuletRulesConfigDetails}
          votingInformation={voteRequest.votingInformation}
          votes={voteRequest.votes}
        />
      </Wrapper>
    );

    const action = screen.getByTestId('proposal-details-action-value');
    expect(action.textContent).toMatch(/Set Amulet Rules Config/);

    const amuletRulesConfigSection = screen.getByTestId('proposal-details-config-changes-section');
    expect(amuletRulesConfigSection).toBeDefined();

    const configChangeContainer = screen.getByTestId('proposal-details-config-changes-section');

    const changes = within(configChangeContainer).getAllByTestId('config-change');
    expect(changes.length).toBe(2);

    const transferCreateFeeFieldName = within(changes[0]).getByTestId('config-change-field-name');
    expect(transferCreateFeeFieldName.textContent).toBe('Transfer (Create Fee)');

    const transferCreateFee = within(changes[0]).getByTestId('config-change-current-value');
    expect(transferCreateFee.textContent).toBe('0.03');

    const newTransferCreateFee = within(changes[0]).getByTestId('config-change-new-value');
    expect(newTransferCreateFee.textContent).toBe('0.04');

    const maxNumInputsFieldName = within(changes[1]).getByTestId('config-change-field-name');
    expect(maxNumInputsFieldName.textContent).toBe('Max Num Inputs');

    const maxNumInputsCurrentValue = within(changes[1]).getByTestId('config-change-current-value');
    expect(maxNumInputsCurrentValue.textContent).toBe('3');

    const maxNumInputsNewValue = within(changes[1]).getByTestId('config-change-new-value');
    expect(maxNumInputsNewValue.textContent).toBe('4');
  });

  test('should render dso rules config changes', () => {
    const dsoRulesConfigDetails = {
      actionName: 'Set DSO Rules Configuration',
      action: 'CRARC_SetConfig',
      proposal: {
        configChanges: [
          {
            fieldName: 'Decentralized Synchronizer (Active Synchronizer)',
            currentValue: 'global-domain::12',
            newValue: 'global-domain::13',
            isId: true,
          },
          {
            fieldName: 'Number of Unclaimed Rewards Threshold',
            currentValue: '10',
            newValue: '20',
          },
        ],
      },
    } as ProposalDetails;

    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={dsoRulesConfigDetails}
          votingInformation={voteRequest.votingInformation}
          votes={voteRequest.votes}
        />
      </Wrapper>
    );

    const action = screen.getByTestId('proposal-details-action-value');
    expect(action.textContent).toMatch(/Set DSO Rules Configuration/);

    const dsoRulesConfigSection = screen.getByTestId('proposal-details-config-changes-section');
    expect(dsoRulesConfigSection).toBeDefined();

    const configChangeContainer = screen.getByTestId('proposal-details-config-changes-section');

    const changes = within(configChangeContainer).getAllByTestId('config-change');
    expect(changes.length).toBe(2);

    const dsoActiveSynchronizerFieldName = within(changes[0]).getByTestId(
      'config-change-field-name'
    );
    expect(dsoActiveSynchronizerFieldName.textContent).toBe(
      'Decentralized Synchronizer (Active Synchronizer)'
    );

    const dsoActiveSynchronizerCurrentValue = within(changes[0]).getByTestId(
      'config-change-current-value-input'
    );
    expect(dsoActiveSynchronizerCurrentValue.getAttribute('value')).toBe('global-domain::12');

    const dsoActiveSynchronizerNewValue = within(changes[0]).getByTestId(
      'config-change-new-value-input'
    );
    expect(dsoActiveSynchronizerNewValue.getAttribute('value')).toBe('global-domain::13');

    const dsoNumUnclaimedRewardsThresholdFieldName = within(changes[1]).getByTestId(
      'config-change-field-name'
    );
    expect(dsoNumUnclaimedRewardsThresholdFieldName.textContent).toBe(
      'Number of Unclaimed Rewards Threshold'
    );

    const dsoNumUnclaimedRewardsThresholdCurrentValue = within(changes[1]).getByTestId(
      'config-change-current-value'
    );
    expect(dsoNumUnclaimedRewardsThresholdCurrentValue.textContent).toBe('10');

    const dsoNumUnclaimedRewardsThresholdNewValue = within(changes[1]).getByTestId(
      'config-change-new-value'
    );
    expect(dsoNumUnclaimedRewardsThresholdNewValue.textContent).toBe('20');
  });
});

const votesData = [
  {
    sv: 'sv1',
    isYou: true,
    vote: 'accepted',
    reason: {
      url: 'https://sv1.example.com',
      body: 'SV1 Reason',
    },
  },
  {
    sv: 'sv2',
    vote: 'rejected',
    reason: {
      url: 'https://sv2.example.com',
      body: 'SV2 Reason',
    },
  },
  {
    sv: 'sv3',
    vote: 'accepted',
    reason: {
      url: '',
      body: '',
    },
  },
  {
    sv: 'sv4',
    vote: 'rejected',
    reason: {
      url: '',
      body: '',
    },
  },
  {
    sv: 'sv5',
    vote: 'no-vote',
    reason: {
      url: '',
      body: '',
    },
  },
] as ProposalVote[];

describe('Proposal Details > Votes & Voting', () => {
  test('should render votes table', () => {
    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={voteRequest.votingInformation}
          votes={votesData}
        />
      </Wrapper>
    );

    const allVotesTab = screen.getByTestId('all-votes-tab');
    const acceptedVotesTab = screen.getByTestId('accepted-votes-tab');
    const rejectedVotesTab = screen.getByTestId('rejected-votes-tab');
    const noVoteVotesTab = screen.getByTestId('no-vote-votes-tab');

    expect(allVotesTab).toBeDefined();
    expect(acceptedVotesTab).toBeDefined();
    expect(rejectedVotesTab).toBeDefined();
    expect(noVoteVotesTab).toBeDefined();

    // Show all votes by default
    expect(allVotesTab.getAttribute('aria-selected')).toBe('true');
    expect(acceptedVotesTab.getAttribute('aria-selected')).toBe('false');
    expect(rejectedVotesTab.getAttribute('aria-selected')).toBe('false');
    expect(noVoteVotesTab.getAttribute('aria-selected')).toBe('false');
  });

  test('should filter votes by tabs', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={voteRequest.votingInformation}
          votes={votesData}
        />
      </Wrapper>
    );

    const allVotesTab = screen.getByTestId('all-votes-tab');
    const acceptedVotesTab = screen.getByTestId('accepted-votes-tab');
    const rejectedVotesTab = screen.getByTestId('rejected-votes-tab');
    const noVoteVotesTab = screen.getByTestId('no-vote-votes-tab');

    await user.click(allVotesTab);
    const allVotes = screen.getAllByTestId('proposal-details-vote');
    expect(allVotes.length).toBe(votesData.length);

    await user.click(acceptedVotesTab);
    const acceptedVotes = screen.getAllByTestId('proposal-details-vote');
    expect(acceptedVotes.length).toBe(2);

    await user.click(rejectedVotesTab);
    const rejectedVotes = screen.getAllByTestId('proposal-details-vote');
    expect(rejectedVotes.length).toBe(2);

    await user.click(noVoteVotesTab);
    const noVoteVotes = screen.getAllByTestId('proposal-details-vote');
    expect(noVoteVotes.length).toBe(1);
  });

  test('should render your vote badge in votes list if you have voted', () => {
    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={voteRequest.votingInformation}
          votes={votesData}
        />
      </Wrapper>
    );

    const yourVoteBadge = screen.getByTestId('proposal-details-your-vote-chip');
    expect(yourVoteBadge).toBeDefined();
  });

  test('should not render your vote badge in votes list if you have not voted', () => {
    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={voteRequest.votingInformation}
          votes={votesData.filter(v => !v.isYou)}
        />
      </Wrapper>
    );

    expect(() => screen.getByTestId('proposal-details-your-vote-chip')).toThrowError(
      /Unable to find an element/
    );
  });

  test('should render status badge in votes list', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={voteRequest.votingInformation}
          votes={votesData}
        />
      </Wrapper>
    );

    const acceptedVotesTab = screen.getByTestId('accepted-votes-tab');
    const rejectedVotesTab = screen.getByTestId('rejected-votes-tab');
    const noVoteVotesTab = screen.getByTestId('no-vote-votes-tab');

    await user.click(acceptedVotesTab);
    const acceptedVotes = screen.getAllByTestId('proposal-details-vote-status-value');
    const acceptedContent = acceptedVotes.map(v => v.textContent);
    expect(acceptedContent.every(v => v === 'Accepted')).toBe(true);

    await user.click(rejectedVotesTab);
    const rejectedVotes = screen.getAllByTestId('proposal-details-vote-status-value');
    const rejectedContent = rejectedVotes.map(v => v.textContent);
    expect(rejectedContent.every(v => v === 'Rejected')).toBe(true);

    await user.click(noVoteVotesTab);
    const noVoteVotes = screen.getAllByTestId('proposal-details-vote-status-value');
    const noVoteContent = noVoteVotes.map(v => v.textContent);
    expect(noVoteContent.every(v => v === 'Awaiting Response')).toBe(true);
  });

  test('should render no-vote status badge when voting has closed', async () => {
    const user = userEvent.setup();
    const votingInformation = {
      requester: 'sv1',
      requesterIsYou: true,
      votingCloses: '2024-01-01 13:00',
      voteTakesEffect: '2029-01-02 13:00',
      status: 'Accepted',
    } as ProposalVotingInformation;

    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={votingInformation}
          votes={votesData}
        />
      </Wrapper>
    );

    const noVoteVotesTab = screen.getByTestId('no-vote-votes-tab');

    await user.click(noVoteVotesTab);
    const noVoteVotes = screen.getAllByTestId('proposal-details-vote-status-value');
    const noVoteContent = noVoteVotes.map(v => v.textContent);
    expect(noVoteContent.every(v => v === 'No Vote')).toBe(true);
  });

  test('renders correctly when vote takes effect is threshold', () => {
    const votingInformation = {
      requester: 'sv1',
      requesterIsYou: true,
      votingCloses: '2029-01-01 13:00',
      voteTakesEffect: 'Threshold',
      status: 'In Progress',
    } as ProposalVotingInformation;

    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={votingInformation}
          votes={voteRequest.votes}
        />
      </Wrapper>
    );

    const votingInformationSection = screen.getByTestId('proposal-details-voting-information');
    expect(votingInformationSection).toBeDefined();

    const voteTakesEffectDuration = within(votingInformationSection).getByTestId(
      'proposal-details-vote-takes-effect-duration'
    );
    expect(voteTakesEffectDuration.textContent).toBe('Threshold');

    expect(() =>
      within(votingInformationSection).getByTestId('proposal-details-vote-takes-effect-value')
    ).toThrowError(/Unable to find an element/);
  });

  test('should render voting form for vote request when voting has not closed', () => {
    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={voteRequest.votingInformation}
          votes={votesData}
        />
      </Wrapper>
    );

    const votingForm = screen.getByTestId('proposal-details-your-vote-section');
    expect(votingForm).toBeDefined();

    const votingFormInput = within(votingForm).getByTestId('proposal-details-your-vote-input');
    expect(votingFormInput).toBeDefined();

    const votingFormAccept = within(votingForm).getByTestId('proposal-details-your-vote-accept');
    expect(votingFormAccept).toBeDefined();

    const votingFormReject = within(votingForm).getByTestId('proposal-details-your-vote-reject');
    expect(votingFormReject).toBeDefined();
  });

  test('should not render voting form for vote request when voting has closed', () => {
    const votingInformation = {
      requester: 'sv1',
      requesterIsYou: true,
      votingCloses: '2024-01-01 13:00',
      voteTakesEffect: '2029-01-02 13:00',
      status: 'In Progress',
    } as ProposalVotingInformation;

    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={votingInformation}
          votes={votesData}
        />
      </Wrapper>
    );

    expect(() => screen.getByTestId('proposal-details-your-vote-section')).toThrowError(
      /Unable to find an element/
    );
  });

  test('should not render voting form for vote result', () => {
    render(
      <Wrapper>
        <ProposalDetailsContent
          contractId={voteResult.contractId}
          proposalDetails={voteResult.proposalDetails}
          votingInformation={voteResult.votingInformation}
          votes={voteResult.votes}
        />
      </Wrapper>
    );

    expect(() => screen.getByTestId('proposal-details-your-vote-section')).toThrowError(
      /Unable to find an element/
    );
  });
});
