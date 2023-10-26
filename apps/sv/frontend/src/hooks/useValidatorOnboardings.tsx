import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend';

import { ValidatorOnboarding } from '@daml.js/validator-lifecycle/lib/CN/ValidatorOnboarding/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useValidatorOnboardings = (): UseQueryResult<Contract<ValidatorOnboarding>[]> => {
  const { listOngoingValidatorOnboardings } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listOngoingValidatorOnboardings'],
    queryFn: async () => {
      const { ongoing_validator_onboardings } = await listOngoingValidatorOnboardings();
      return ongoing_validator_onboardings.map(c => Contract.decodeOpenAPI(c, ValidatorOnboarding));
    },
  });
};
