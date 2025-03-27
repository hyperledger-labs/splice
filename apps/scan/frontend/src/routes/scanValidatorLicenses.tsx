// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ValidatorLicenses } from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import Layout from '../components/Layout';
import { useDsoInfos, useValidatorLicenses } from '../hooks';

const ScanValidatorLicenses: React.FC = () => {
  const validatorLicensesQuery = useValidatorLicenses(10);
  const dsoInfosQuery = useDsoInfos();
  return (
    <Layout>
      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg">
          <ValidatorLicenses
            validatorLicensesQuery={validatorLicensesQuery}
            dsoInfosQuery={dsoInfosQuery}
          />
        </Container>
      </Box>
    </Layout>
  );
};

export default ScanValidatorLicenses;
