// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { SvClientProvider, ValidatorLicenses } from 'common-frontend';
import React from 'react';

import { useDsoInfos } from '../contexts/SvContext';
import { useValidatorLicenses } from '../hooks/useValidatorLicenses';
import { useSvConfig } from '../utils';

const ValidatorLicensesWithContexts: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ValidatorLicensesWithQueries />
    </SvClientProvider>
  );
};

const ValidatorLicensesWithQueries: React.FC = () => {
  const validatorLicensesQuery = useValidatorLicenses(10);
  const dsoInfosQuery = useDsoInfos();
  return (
    <ValidatorLicenses
      validatorLicensesQuery={validatorLicensesQuery}
      dsoInfosQuery={dsoInfosQuery}
    />
  );
};

export default ValidatorLicensesWithContexts;
