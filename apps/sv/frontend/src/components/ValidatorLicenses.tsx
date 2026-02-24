// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ValidatorLicenses } from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';
import { Box } from '@mui/material';

import { useDsoInfos } from '../contexts/SvContext';
import { useValidatorLicenses } from '../hooks/useValidatorLicenses';

const ValidatorLicensesWithContexts: React.FC = () => {
  return <ValidatorLicensesWithQueries />;
};

const ValidatorLicensesWithQueries: React.FC = () => {
  const validatorLicensesQuery = useValidatorLicenses(10);
  const dsoInfosQuery = useDsoInfos();

  return (
    <Box mt={4}>
      <ValidatorLicenses
        validatorLicensesQuery={validatorLicensesQuery}
        dsoInfosQuery={dsoInfosQuery}
      />
    </Box>
  );
};

export default ValidatorLicensesWithContexts;
