// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { SvClientProvider, SvVote } from '@lfdecentralizedtrust/splice-common-frontend';
import { ListVoteRequests } from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';

import { useSvConfig } from '../../utils';
import VoteForm from './VoteForm';

const SvListVoteRequests: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ListVoteRequests
        showActionNeeded
        voteForm={(requestContractId: ContractId<VoteRequest>, currentSvVote?: SvVote) => (
          <VoteForm voteRequestCid={requestContractId} vote={currentSvVote} />
        )}
      />
    </SvClientProvider>
  );
};

export default SvListVoteRequests;
