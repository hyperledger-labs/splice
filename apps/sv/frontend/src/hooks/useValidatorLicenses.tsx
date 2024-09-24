// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseInfiniteQueryResult, useInfiniteQuery } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { ValidatorLicense } from '@daml.js/splice-amulet/lib/Splice/ValidatorLicense/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

interface ValidatorLicensesPage {
  validatorLicenses: Contract<ValidatorLicense>[];
  after?: number;
}

export const useValidatorLicenses = (
  limit: number
): UseInfiniteQueryResult<ValidatorLicensesPage> => {
  const { listValidatorLicenses } = useSvAdminClient();
  return useInfiniteQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listValidatorLicenses', limit],
    queryFn: async ({ pageParam }) => {
      const page = await listValidatorLicenses(limit, pageParam);
      const validatorLicenses = page.validator_licenses.map(c =>
        Contract.decodeOpenAPI(c, ValidatorLicense)
      );
      return validatorLicenses.length === 0
        ? undefined
        : { validatorLicenses, after: page.next_page_token };
    },
    getNextPageParam: lastPage => {
      return lastPage && lastPage.after;
    },
  });
};
