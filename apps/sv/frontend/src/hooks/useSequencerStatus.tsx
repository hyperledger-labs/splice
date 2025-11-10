// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { NodeStatus } from '@lfdecentralizedtrust/sv-openapi';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useSequencerStatus = (): UseQueryResult<NodeStatus> => {
  const { getSequencerNodeStatus } = useSvAdminClient();
  return useQuery({
    queryKey: ['getSequencerNodeStatus'],
    queryFn: async () => await getSequencerNodeStatus(),
    refetchInterval: 10_000,
  });
};
