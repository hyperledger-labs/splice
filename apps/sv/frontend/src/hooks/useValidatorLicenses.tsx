// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { ValidatorLicense } from '@daml.js/splice-amulet/lib/Splice/ValidatorLicense/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useValidatorLicenses = (): UseQueryResult<Contract<ValidatorLicense>[]> => {
  const { listValidatorLicenses } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listValidatorLicenses'],
    queryFn: async () => {
      const { validator_licenses } = await listValidatorLicenses();
      return validator_licenses.map(c => Contract.decodeOpenAPI(c, ValidatorLicense));
    },
  });
};
