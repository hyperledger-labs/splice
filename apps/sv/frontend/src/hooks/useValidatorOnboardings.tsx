import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend';
import { ValidatorOnboarding } from 'common-frontend/daml.js/validator-lifecycle-0.1.0/lib/CN/ValidatorOnboarding/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useValidatorOnboardings: () => UseQueryResult<
  Contract<ValidatorOnboarding>[]
> = () => {
  const { listOngoingValidatorOnboardings } = useSvAdminClient();
  return useQuery({
    queryKey: ['listOngoingValidatorOnboardings'],
    queryFn: async () => {
      const { ongoingValidatorOnboardings } = await listOngoingValidatorOnboardings();
      return ongoingValidatorOnboardings;
    },
  });
};
