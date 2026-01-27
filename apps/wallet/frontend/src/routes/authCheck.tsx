// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AuthConfig,
  ErrorDisplay,
  Loading,
  Login,
  TestAuthConfig,
  useUserState,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Outlet } from 'react-router';

import { BasicLayout } from '../components/Layout';
import Onboarding from '../components/Onboarding';
import { useIsOnboarded } from '../hooks';
import { useWalletConfig } from '../utils/config';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const config = useWalletConfig();
  const { isAuthenticated } = useUserState();
  const { isLoading, isError, data: isOnboarded } = useIsOnboarded();

  const title = `${config.spliceInstanceNames.amuletName} Wallet`;

  if (!isAuthenticated) {
    return <Login title={title} authConfig={authConfig} testAuthConfig={testAuthConfig} />;
  }

  if (isLoading) {
    return (
      <BasicLayout>
        <Loading />
      </BasicLayout>
    );
  }

  if (isError) {
    return <ErrorDisplay message={'Error while fetching user status'} />;
  }

  if (isOnboarded) {
    return <Outlet />;
  } else {
    return <Onboarding />;
  }
};

export default AuthCheck;
