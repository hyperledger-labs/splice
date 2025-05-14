// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { InfiniteData, useInfiniteQuery, UseInfiniteQueryResult } from '@tanstack/react-query';

import { ValidatorLicense } from '@daml.js/splice-amulet/lib/Splice/ValidatorLicense/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

interface ValidatorLicensesPage {
  validatorLicenses: Contract<ValidatorLicense>[];
  after: number | undefined;
}

export const useValidatorLicenses: (
  limit: number
) => UseInfiniteQueryResult<InfiniteData<ValidatorLicensesPage>> = (limit: number) => {
  const { listValidatorLicenses } = useSvAdminClient();
  return useInfiniteQuery({
    queryKey: ['listValidatorLicenses', limit],
    queryFn: async ({ pageParam }) => {
      const page = await listValidatorLicenses(limit, pageParam === 0 ? undefined : pageParam);
      const validatorLicenses = page.validator_licenses.map(c =>
        Contract.decodeOpenAPI(c, ValidatorLicense)
      );
      return { validatorLicenses, after: page.next_page_token };
    },
    initialPageParam: 0,
    getNextPageParam: lastPage => {
      return lastPage && lastPage.after;
    },
  });
};
