// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AmuletPriceVote,
  DsoInfo,
  SvVote,
  theme,
  VotesHooks,
  VotesHooksContext,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import {
  dsoInfo,
  getDsoSvOffboardingAction,
} from '@lfdecentralizedtrust/splice-common-test-handlers';
import { QueryClient, QueryClientProvider, useQuery, UseQueryResult } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import dayjs from 'dayjs';
import React from 'react';
import { describe, expect, test } from 'vitest';

import { ThemeProvider } from '@mui/material';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import {
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import * as constants from '../mocks/constants';
import { ListVoteRequests } from '../../components';
import VoteModalContent, { VoteRow } from '../../components/votes/VoteModalContent';

const queryClient = new QueryClient();
// The linter wants me to add the constants.X in the queryKey,
// which is better than disabling the lint because having a bad query key is BAD
const provider: VotesHooks = {
  isReadOnly: true,
  useDsoInfos(): UseQueryResult<DsoInfo> {
    return useQuery({
      queryKey: ['useDsoInfos', DsoRules, AmuletRules, dsoInfo],
      queryFn: async () => {
        return {
          svUser: dsoInfo.sv_user,
          svPartyId: dsoInfo.sv_party_id,
          dsoPartiId: dsoInfo.dso_party_id,
          votingThreshold: dsoInfo.voting_threshold,
          amuletRules: Contract.decodeOpenAPI(dsoInfo.amulet_rules.contract, AmuletRules),
          dsoRules: Contract.decodeOpenAPI(dsoInfo.dso_rules.contract, DsoRules),
          nodeStates: [],
        };
      },
    });
  },
  useListDsoRulesVoteRequests(): UseQueryResult<Contract<VoteRequest>[]> {
    return useQuery({
      queryKey: ['useListDsoRulesVoteRequests', constants.votedRequest, constants.unvotedRequest],
      queryFn: async () => {
        return [constants.votedRequest, constants.unvotedRequest];
      },
    });
  },
  useListVoteRequestResult(
    _limit: number,
    _actionName: string | undefined,
    _requester: string | undefined,
    effectiveFrom: string | undefined,
    _effectiveTo: string | undefined,
    executed: boolean | undefined
  ): UseQueryResult<DsoRules_CloseVoteRequestResult[]> {
    return useQuery({
      queryKey: [
        'useListVoteRequestResult',
        effectiveFrom,
        executed,
        constants.rejectedVoteResult,
        constants.plannedVoteResult,
        constants.executedVoteResult,
      ],
      queryFn: async () => {
        if (executed === false) {
          return [constants.rejectedVoteResult];
        } else if (effectiveFrom) {
          return [constants.plannedVoteResult];
        } else {
          return [constants.executedVoteResult];
        }
      },
    });
  },
  useListVotes(contractIds: ContractId<VoteRequest>[]): UseQueryResult<SvVote[]> {
    return useQuery({
      queryKey: [
        'useListVotes',
        contractIds,
        constants.rejectedVoteResult,
        constants.unvotedRequest,
      ],
      queryFn: async () => {
        console.log(`Called with ${contractIds}`);
        return contractIds.flatMap(cid => {
          return cid === constants.unvotedRequest.contractId
            ? []
            : [constants.myVote(cid, cid === constants.rejectedVoteResult.request.trackingCid)];
        });
      },
    });
  },
  useAmuletPriceVotes(): UseQueryResult<AmuletPriceVote[]> {
    return useQuery({
      queryKey: ['useAmuletPriceVotes', constants.amuletPriceVotes],
      queryFn: async () => {
        return constants.amuletPriceVotes;
      },
    });
  },
  useVoteRequest(contractId: ContractId<VoteRequest>): UseQueryResult<Contract<VoteRequest>> {
    return useQuery({
      queryKey: ['useVoteRequest', contractId, constants.votedRequest, constants.unvotedRequest],
      queryFn: async () =>
        [constants.votedRequest, constants.unvotedRequest].filter(
          req => req.contractId === contractId
        )[0],
    });
  },
};

const TestVotes: React.FC<{ showActionNeeded: boolean }> = ({ showActionNeeded }) => {
  return (
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <VotesHooksContext.Provider value={provider}>
          <ListVoteRequests showActionNeeded={showActionNeeded} />
        </VotesHooksContext.Provider>
      </QueryClientProvider>
    </ThemeProvider>
  );
};

describe('Votes list should', () => {
  test('Show votes requiring action, when that is enabled', async () => {
    render(<TestVotes showActionNeeded />);

    const actionNeeded = await screen.findByText('Action Needed');
    expect(actionNeeded).toBeDefined();
    fireEvent.click(actionNeeded);
    // TODO(#712): Test diffs for SRARC_UpdateSvRewardWeight
    const actionNeededRows = await screen.findAllByText('SRARC_UpdateSvRewardWeight');
    expect(actionNeededRows).toHaveLength(2);
  });

  test('NOT Show votes requiring action, when that is disabled', async () => {
    render(<TestVotes showActionNeeded={false} />);
    expect(screen.queryByText('Action Needed')).toBeNull();
  });

  test('Show votes that are executed', async () => {
    render(<TestVotes showActionNeeded />);

    const planned = await screen.findByText('Executed');
    expect(planned).toBeDefined();
    fireEvent.click(planned);

    const plannedRows = await screen.findAllByText('SRARC_UpdateSvRewardWeight');
    expect(plannedRows).toHaveLength(1);
  });

  test('Show votes that are rejected', async () => {
    render(<TestVotes showActionNeeded />);

    const planned = await screen.findByText('Rejected');
    expect(planned).toBeDefined();
    fireEvent.click(planned);

    const plannedRows = await screen.findAllByText('SRARC_UpdateSvRewardWeight');
    expect(plannedRows).toHaveLength(1);
  });
});

describe('Vote Modal', () => {
  test('displays a valid expiry date when vote request expires in the future', async () => {
    const expiryDate = dayjs().add(1, 'day');
    const expected = `${expiryDate.format('YYYY-MM-DD HH:mm')} (in a day)`;

    render(
      <ThemeProvider theme={theme}>
        <QueryClientProvider client={queryClient}>
          <VotesHooksContext.Provider value={provider}>
            <VoteModalContent
              voteRequestContractId={'contractId' as ContractId<VoteRequest>}
              actionReq={getDsoSvOffboardingAction('sv1')}
              requester="sv1"
              getMemberName={() => 'sv1'}
              reason={{ body: 'reason', url: 'url' }}
              voteBefore={expiryDate.toDate()}
              rejectedVotes={[]}
              acceptedVotes={[]}
            />
          </VotesHooksContext.Provider>
        </QueryClientProvider>
      </ThemeProvider>
    );

    const expiryDateField = await screen.findByTestId('vote-request-modal-expires-at');
    expect(expiryDateField.textContent).toEqual(expected);
  });

  test('displays Did not expire when vote request is past expiry date but threshold was reached', async () => {
    render(
      <ThemeProvider theme={theme}>
        <QueryClientProvider client={queryClient}>
          <VotesHooksContext.Provider value={provider}>
            <VoteModalContent
              voteRequestContractId={'contractId' as ContractId<VoteRequest>}
              actionReq={getDsoSvOffboardingAction('sv1')}
              requester="sv1"
              getMemberName={() => 'sv1'}
              reason={{ body: 'reason', url: 'https://vote-request-url.com' }}
              voteBefore={dayjs().subtract(1, 'day').toDate()}
              rejectedVotes={[]}
              acceptedVotes={[]}
            />
          </VotesHooksContext.Provider>
        </QueryClientProvider>
      </ThemeProvider>
    );

    const expiryDate = await screen.findByTestId('vote-request-modal-expires-at');
    expect(expiryDate.textContent).toEqual('Did not expire');
  });
});

describe('security checks', () => {
  test('should not alter a valid url', async () => {
    render(
      <ThemeProvider theme={theme}>
        <VoteRow
          svName="sv1"
          sv="sv1"
          reasonBody="reasonBody"
          reasonUrl="https://vote-request-url.com/"
        />
      </ThemeProvider>
    );

    const urlText = screen.getByText('https://vote-request-url.com/');
    expect(urlText).toBeDefined();
  });

  test('should sanitize displayed proposal url', async () => {
    render(
      <ThemeProvider theme={theme}>
        <QueryClientProvider client={queryClient}>
          <VotesHooksContext.Provider value={provider}>
            <VoteModalContent
              voteRequestContractId={'contractId' as ContractId<VoteRequest>}
              actionReq={getDsoSvOffboardingAction('sv1')}
              requester="sv1"
              getMemberName={() => 'sv1'}
              reason={{ body: 'reason', url: 'javascript:alert(document.domain)' }}
              voteBefore={dayjs().subtract(1, 'day').toDate()}
              rejectedVotes={[]}
              acceptedVotes={[]}
            />
          </VotesHooksContext.Provider>
        </QueryClientProvider>
      </ThemeProvider>
    );

    const urlElement = screen.getByTestId('vote-request-modal-reason-url');
    const href = urlElement.getAttribute('href');
    const displayText = urlElement.textContent;

    expect(href).toBe('about:blank');
    expect(displayText).toBe('about:blank');
  });

  test('should sanitize displayed vote url', () => {
    render(
      <ThemeProvider theme={theme}>
        <VoteRow
          svName="sv1"
          sv="sv1"
          reasonBody="reasonBody"
          reasonUrl="javascript:alert(document.domain)"
        />
      </ThemeProvider>
    );

    const urlElement = screen.getByTestId('vote-row-reason-url');
    const href = urlElement.getAttribute('href');
    const displayText = urlElement.textContent;

    expect(href).toBe('about:blank');
    expect(displayText).toBe('about:blank');
  });
});
