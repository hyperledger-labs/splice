// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { SvClientProvider, SvVote } from 'common-frontend';
import { ListVoteRequests } from 'common-frontend';
import React from 'react';

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';

import { SvAppVotesHooksProvider } from '../../contexts/SvAppVotesHooksContext';
import { useSvConfig } from '../../utils';
import VoteForm from './VoteForm';

const SvListVoteRequests: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <SvAppVotesHooksProvider>
        <ListVoteRequests
          showActionNeeded
          voteForm={(requestContractId: ContractId<VoteRequest>, currentSvVote?: SvVote) => (
            <VoteForm voteRequestCid={requestContractId} vote={currentSvVote} />
          )}
        />
      </SvAppVotesHooksProvider>
    </SvClientProvider>
  );
};

export default SvListVoteRequests;
