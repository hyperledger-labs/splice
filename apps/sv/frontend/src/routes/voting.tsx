// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';

import { Box } from '@mui/material';

import VoteRequest from '../components/votes/VoteRequest';
import { useDsoInfos, useFeatureSupport } from '../contexts/SvContext';

const Voting: React.FC = () => {
  const dsoInfosQuery = useDsoInfos();
  const featureSupport = useFeatureSupport();
  if (dsoInfosQuery.isLoading || featureSupport.isLoading) {
    return <Loading />;
  }

  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }

  if (!dsoInfosQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }
  const supportNewGovernanceFlow = featureSupport.data?.newGovernanceFlow || false;
  //TODO(#16139): retire this logic
  return (
    <Box>{<VoteRequest supportsVoteEffectivityAndSetConfig={supportNewGovernanceFlow} />}</Box>
  );
};

export default Voting;
