// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AuthConfig,
  TestAuthConfig,
  Login,
  useUserState,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Outlet } from 'react-router';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const { isAuthenticated } = useUserState();

  if (isAuthenticated) {
    return <Outlet />;
  } else {
    return <Login title="Splitwell" authConfig={authConfig} testAuthConfig={testAuthConfig} />;
  }
};

export default AuthCheck;
