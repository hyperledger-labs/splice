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
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { ThemeProvider } from '@mui/material';
import { ProposalDetails, ProposalVote, ProposalVotingInformation } from '../../utils/types';
import userEvent from '@testing-library/user-event';
import { SvConfigProvider, useSvConfig } from '../../utils';
import {
  AuthProvider,
  SvClientProvider,
  theme,
  UserProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { SvAdminClientProvider } from '../../contexts/SvAdminServiceContext';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { replaceEqualDeep } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { server, svUrl } from '../setup/setup';
import { rest } from 'msw';
import { ProposalVoteForm } from '../../components/governance/ProposalVoteForm';
import App from '../../App';
import { svPartyId } from '../mocks/constants';

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
    isVoteRequest: false,
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

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchInterval: 500,
      structuralSharing: replaceEqualDeep,
    },
  },
});

const Wrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  return (
    <MemoryRouter>
      <SvConfigProvider>
        <WrapperProviders children={children} />
      </SvConfigProvider>
    </MemoryRouter>
  );
};

const WrapperProviders: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const config = useSvConfig();
  const navigate = useNavigate();

  return (
    <ThemeProvider theme={theme}>
      <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
        <QueryClientProvider client={queryClient}>
          <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
            <SvClientProvider url={config.services.sv.url}>
              <SvAdminClientProvider url={config.services.sv.url}>{children}</SvAdminClientProvider>
            </SvClientProvider>
          </UserProvider>
        </QueryClientProvider>
      </AuthProvider>
    </ThemeProvider>
  );
};

describe('SV user can', () => {
  test('login and see the SV party ID', async () => {
    const user = userEvent.setup();
    render(
      <SvConfigProvider>
        <App />
      </SvConfigProvider>
    );

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    const button = screen.getByRole('button', { name: 'Log In' });
    user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });
});

describe('Proposal Details Content', () => {
  test('should render proposal details page', async () => {
    render(
      <Wrapper>
        <ProposalDetailsContent
          currentSvPartyId={voteRequest.votingInformation.requester}
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

    expect(screen.getByTestId('your-vote-form')).toBeDefined();
    expect(screen.getByTestId('your-vote-url-input')).toBeDefined();
    expect(screen.getByTestId('your-vote-reason-input')).toBeDefined();
    expect(screen.getByTestId('your-vote-accept')).toBeDefined();
    expect(screen.getByTestId('your-vote-reject')).toBeDefined();
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
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
          currentSvPartyId={voteRequest.votingInformation.requester}
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={voteRequest.votingInformation}
          votes={votesData}
        />
      </Wrapper>
    );

    const votingForm = screen.getByTestId('your-vote-form');
    expect(votingForm).toBeDefined();

    const votingFormUrlInput = within(votingForm).getByTestId('your-vote-url-input');
    expect(votingFormUrlInput).toBeDefined();

    const votingFormReasonInput = within(votingForm).getByTestId('your-vote-reason-input');
    expect(votingFormReasonInput).toBeDefined();

    const votingFormAccept = within(votingForm).getByTestId('your-vote-accept');
    expect(votingFormAccept).toBeDefined();

    const votingFormReject = within(votingForm).getByTestId('your-vote-reject');
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
          currentSvPartyId={voteRequest.votingInformation.requester}
          contractId={voteRequest.contractId}
          proposalDetails={voteRequest.proposalDetails}
          votingInformation={votingInformation}
          votes={votesData}
        />
      </Wrapper>
    );

    expect(() => screen.getByTestId('your-vote-form')).toThrowError(/Unable to find an element/);
  });

  test('should not render voting form for vote result', () => {
    render(
      <Wrapper>
        <ProposalDetailsContent
          currentSvPartyId={voteRequest.votingInformation.requester}
          contractId={voteResult.contractId}
          proposalDetails={voteResult.proposalDetails}
          votingInformation={voteResult.votingInformation}
          votes={voteResult.votes}
        />
      </Wrapper>
    );

    expect(() => screen.getByTestId('your-vote-form')).toThrowError(/Unable to find an element/);
  });

  test('submit button says Submit if sv has not voted', async () => {
    const votes: ProposalVote[] = [
      {
        sv: 'sv1',
        vote: 'no-vote',
      },
    ];

    render(
      <Wrapper>
        <ProposalVoteForm
          voteRequestContractId={voteRequest.contractId}
          currentSvPartyId={'sv1'}
          votes={votes}
        />
      </Wrapper>
    );

    const votingForm = screen.getByTestId('your-vote-form');
    const submitButton = within(votingForm).getByTestId('submit-vote-button');

    expect(submitButton).toBeDefined();
    expect(submitButton.textContent).toMatch(/Submit/);
  });

  test('submit button says Update if sv has already voted', async () => {
    const votes: ProposalVote[] = [
      {
        sv: 'sv1',
        vote: 'accepted',
        reason: {
          url: 'https://sv1.example.com',
          body: 'SV1 Reason',
        },
      },
    ];

    render(
      <Wrapper>
        <ProposalVoteForm
          voteRequestContractId={voteRequest.contractId}
          currentSvPartyId={'sv1'}
          votes={votes}
        />
      </Wrapper>
    );

    const votingForm = screen.getByTestId('your-vote-form');
    const submitButton = within(votingForm).getByTestId('submit-vote-button');

    expect(submitButton).toBeDefined();
    expect(submitButton.textContent).toMatch(/Update/);
  });

  test('render success message after api returns success', async () => {
    const votes: ProposalVote[] = [
      {
        sv: 'sv1',
        vote: 'no-vote',
      },
      {
        sv: 'sv2',
        vote: 'accepted',
        reason: {
          url: 'https://sv2.example.com',
          body: 'SV2 Reason',
        },
      },
    ];

    server.use(
      rest.post(`${svUrl}/v0/admin/sv/votes`, (_, res, ctx) => {
        return res(ctx.status(201));
      })
    );

    const user = userEvent.setup();

    render(
      <Wrapper>
        <ProposalVoteForm
          voteRequestContractId={voteRequest.contractId}
          currentSvPartyId={'sv1'}
          votes={votes}
        />
      </Wrapper>
    );

    const votingForm = screen.getByTestId('your-vote-form');
    expect(votingForm).toBeDefined();

    const urlInput = within(votingForm).getByTestId('your-vote-url-input');
    expect(urlInput).toBeDefined();

    const reasonInput = within(votingForm).getByTestId('your-vote-reason-input');
    expect(reasonInput).toBeDefined();

    const acceptRadio = within(votingForm).getByTestId('your-vote-accept');
    expect(acceptRadio).toBeDefined();

    await user.click(acceptRadio);

    const submitButton = within(votingForm).getByTestId('submit-vote-button');
    expect(submitButton).toBeDefined();

    await user.click(submitButton);

    const submissionMessage = await screen.findByTestId('submission-message');
    expect(submissionMessage).toBeDefined();

    const successMessage = await screen.findByTestId('vote-submission-success');

    expect(successMessage.textContent).toMatch(/Vote successfully updated/);
  });

  test('render failure message after api returns error', async () => {
    const votes: ProposalVote[] = [
      {
        sv: 'sv1',
        vote: 'no-vote',
      },
      {
        sv: 'sv2',
        vote: 'accepted',
        reason: {
          url: 'https://sv2.example.com',
          body: 'SV2 Reason',
        },
      },
    ];

    server.use(
      rest.post(`${svUrl}/v0/admin/sv/votes`, (_, res, ctx) => {
        return res(ctx.status(400));
      })
    );

    const user = userEvent.setup();

    render(
      <Wrapper>
        <ProposalVoteForm
          voteRequestContractId={voteRequest.contractId}
          currentSvPartyId={'sv1'}
          votes={votes}
        />
      </Wrapper>
    );

    const votingForm = screen.getByTestId('your-vote-form');
    expect(votingForm).toBeDefined();

    const urlInput = within(votingForm).getByTestId('your-vote-url-input');
    expect(urlInput).toBeDefined();

    const reasonInput = within(votingForm).getByTestId('your-vote-reason-input');
    expect(reasonInput).toBeDefined();

    const acceptRadio = within(votingForm).getByTestId('your-vote-accept');
    expect(acceptRadio).toBeDefined();

    await user.click(acceptRadio);

    const submitButton = within(votingForm).getByTestId('submit-vote-button');
    expect(submitButton).toBeDefined();

    await user.click(submitButton);

    const submissionMessage = await screen.findByTestId('submission-message');
    expect(submissionMessage).toBeDefined();

    const successMessage = await screen.findByTestId('vote-submission-error');

    expect(successMessage.textContent).toMatch(/Something went wrong, unable to cast vote/);
  });

  test('prevent submission if provided url is invalid', async () => {
    const votes: ProposalVote[] = [
      {
        sv: 'sv1',
        vote: 'no-vote',
      },
      {
        sv: 'sv2',
        vote: 'accepted',
        reason: {
          url: 'https://sv2.example.com',
          body: 'SV2 Reason',
        },
      },
    ];

    const user = userEvent.setup();

    render(
      <Wrapper>
        <ProposalVoteForm
          voteRequestContractId={voteRequest.contractId}
          currentSvPartyId={'sv1'}
          votes={votes}
        />
      </Wrapper>
    );

    const votingForm = screen.getByTestId('your-vote-form');
    expect(votingForm).toBeDefined();

    const urlInput = within(votingForm).getByTestId('your-vote-url-input');
    expect(urlInput).toBeDefined();

    await user.type(urlInput, 'invalid_url');

    const acceptRadio = within(votingForm).getByTestId('your-vote-accept');
    user.click(acceptRadio);

    const submitButton = screen.getByTestId('submit-vote-button');
    expect(submitButton.getAttribute('disabled')).toBeDefined();

    const urlHelperText = within(votingForm).getByTestId('your-vote-url-helper-text');
    expect(urlHelperText.textContent).toMatch(/Invalid URL/);
  });

  test('prevent submission if vote has not been chosen', async () => {
    const votes: ProposalVote[] = [
      {
        sv: 'sv1',
        vote: 'no-vote',
      },
      {
        sv: 'sv2',
        vote: 'accepted',
        reason: {
          url: 'https://sv2.example.com',
          body: 'SV2 Reason',
        },
      },
    ];

    const user = userEvent.setup();

    render(
      <Wrapper>
        <ProposalVoteForm
          voteRequestContractId={voteRequest.contractId}
          currentSvPartyId={'sv1'}
          votes={votes}
        />
      </Wrapper>
    );

    const votingForm = screen.getByTestId('your-vote-form');
    expect(votingForm).toBeDefined();

    const submitButton = screen.getByTestId('submit-vote-button');
    expect(submitButton.getAttribute('disabled')?.valueOf()).toBe('');

    const rejectRadio = within(votingForm).getByTestId('your-vote-reject');
    await user.click(rejectRadio);

    expect(submitButton.getAttribute('disabled')?.valueOf()).toBe(undefined);
  });
});
