// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';
import { NodeStatus } from 'sv-openapi';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useSequencerStatus = (): UseQueryResult<NodeStatus> => {
  const { getSequencerNodeStatus } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['getSequencerNodeStatus'],
    queryFn: async () => await getSequencerNodeStatus(),
  });
};
