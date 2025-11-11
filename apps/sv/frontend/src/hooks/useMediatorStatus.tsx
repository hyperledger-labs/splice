// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { NodeStatus } from '@lfdecentralizedtrust/sv-openapi';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useMediatorStatus = (): UseQueryResult<NodeStatus> => {
  const { getMediatorNodeStatus } = useSvAdminClient();
  return useQuery({
    queryKey: ['getMediatorNodeStatus'],
    queryFn: async () => await getMediatorNodeStatus(),
    refetchInterval: 10_000,
  });
};
