import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend';
import { ValidatorLicense } from 'common-frontend/daml.js/canton-coin-0.1.0/lib/CC/ValidatorLicense/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useValidatorLicenses = (): UseQueryResult<Contract<ValidatorLicense>[]> => {
  const { listValidatorLicenses } = useSvAdminClient();
  return useQuery({
    queryKey: ['listValidatorLicenses'],
    queryFn: async () => {
      const { validator_licenses } = await listValidatorLicenses();
      return validator_licenses;
    },
  });
};
