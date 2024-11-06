// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseInfiniteQueryResult, useInfiniteQuery } from '@tanstack/react-query';
import { ValidatorLicensesPage } from 'common-frontend';
import { Contract } from 'common-frontend-utils';

import { ValidatorLicense } from '@daml.js/splice-amulet/lib/Splice/ValidatorLicense/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useValidatorLicenses = (
  limit: number
): UseInfiniteQueryResult<ValidatorLicensesPage> => {
  const { listValidatorLicenses } = useSvAdminClient();
  return useInfiniteQuery({
    queryKey: ['listValidatorLicenses', limit],
    queryFn: async ({ pageParam }) => {
      const page = await listValidatorLicenses(limit, pageParam);
      const validatorLicenses = page.validator_licenses.map(c =>
        Contract.decodeOpenAPI(c, ValidatorLicense)
      );
      return { validatorLicenses, after: page.next_page_token };
    },
    getNextPageParam: lastPage => {
      return lastPage && lastPage.after;
    },
  });
};
