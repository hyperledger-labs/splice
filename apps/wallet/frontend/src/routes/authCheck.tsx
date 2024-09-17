// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  ErrorDisplay,
  Loading,
  Login,
  useUserState,
  AuthConfig,
  TestAuthConfig,
} from 'common-frontend';
import { Outlet } from 'react-router-dom';

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
    return <Loading />;
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
