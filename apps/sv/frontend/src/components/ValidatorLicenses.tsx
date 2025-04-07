// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ValidatorLicenses } from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';

import { useDsoInfos } from '../contexts/SvContext';
import { useValidatorLicenses } from '../hooks/useValidatorLicenses';

const ValidatorLicensesWithContexts: React.FC = () => {
  return <ValidatorLicensesWithQueries />;
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
