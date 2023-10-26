import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend';

import { ValidatorLicense } from '@daml.js/canton-coin/lib/CC/ValidatorLicense/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useValidatorLicenses = (): UseQueryResult<Contract<ValidatorLicense>[]> => {
  const { listValidatorLicenses } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listValidatorLicenses'],
    queryFn: async () => {
      const { validator_licenses } = await listValidatorLicenses();
      return validator_licenses;
    },
  });
};
