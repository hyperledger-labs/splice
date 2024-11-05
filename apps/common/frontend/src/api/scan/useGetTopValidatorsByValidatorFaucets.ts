// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetTopValidatorsByValidatorFaucetsResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useGetTopValidatorsByValidatorFaucets =
  (): UseQueryResult<GetTopValidatorsByValidatorFaucetsResponse> => {
    const scanClient = useScanClient();

    return useQuery({
      queryKey: ['scan-api', 'getTopValidatorsByValidatorFaucets'],
      queryFn: async () => scanClient.getTopValidatorsByValidatorFaucets(10),
      enabled: true,
    });
  };

export default useGetTopValidatorsByValidatorFaucets;
