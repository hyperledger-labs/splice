// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ValidatorLicensesPage } from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useScanClient } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { InfiniteData, UseInfiniteQueryResult, useInfiniteQuery } from '@tanstack/react-query';

import { ValidatorLicense } from '@daml.js/splice-amulet/lib/Splice/ValidatorLicense/module';

export const useValidatorLicenses = (
  limit: number
): UseInfiniteQueryResult<InfiniteData<ValidatorLicensesPage>> => {
  const scanClient = useScanClient();
  return useInfiniteQuery({
    queryKey: ['listValidatorLicenses', limit],
    queryFn: async ({ pageParam }) => {
      const page = await scanClient.listValidatorLicenses(
        pageParam === 0 ? undefined : pageParam,
        limit
      );
      const validatorLicenses = page.validator_licenses.map(c =>
        Contract.decodeOpenAPI(c, ValidatorLicense)
      );
      return { validatorLicenses, after: page.next_page_token };
    },
    getNextPageParam: lastPage => {
      return lastPage && lastPage.after;
    },
    initialPageParam: 0,
  });
};
