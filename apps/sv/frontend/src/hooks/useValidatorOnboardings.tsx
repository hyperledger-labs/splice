// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';

import { ValidatorOnboarding } from '@daml.js/splice-validator-lifecycle/lib/Splice/ValidatorOnboarding/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export type ValidatorOnboardingSecret = {
  encodedSecret: string;
  contract: Contract<ValidatorOnboarding>;
};

export const useValidatorOnboardings = (): UseQueryResult<ValidatorOnboardingSecret[]> => {
  const { listOngoingValidatorOnboardings } = useSvAdminClient();
  return useQuery({
    queryKey: ['listOngoingValidatorOnboardings'],
    queryFn: async () => {
      const { ongoing_validator_onboardings } = await listOngoingValidatorOnboardings();
      return ongoing_validator_onboardings.map(c => ({
        encodedSecret: c.encoded_secret,
        contract: Contract.decodeOpenAPI(c.contract, ValidatorOnboarding),
      }));
    },
  });
};
