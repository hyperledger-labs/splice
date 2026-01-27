// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AuthConfig,
  TestAuthConfig,
  Loading,
  Login,
  useUserState,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useEffect, useState } from 'react';
import { Outlet } from 'react-router';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

interface AuthCheckProps {
  authConfig: AuthConfig;
  testAuthConfig?: TestAuthConfig;
}

const AuthCheck: React.FC<AuthCheckProps> = ({ authConfig, testAuthConfig }) => {
  const { userAccessToken, isAuthenticated } = useUserState();
  const svClient = useSvAdminClient();
  type AuthorizationState = 'isLoading' | 'ok' | 'unauthorized' | 'failed' | 'unset';
  const [isAuthorized, setIsAuthorized] = useState<AuthorizationState>('unset');

  useEffect(() => {
    svClient
      .isAuthorized()
      .then(() => setIsAuthorized('ok'))
      .catch(error => {
        if (error.code === 403) {
          setIsAuthorized('unauthorized');
        } else {
          setIsAuthorized('failed');
        }
      });
  }, [svClient, userAccessToken]);

  useEffect(() => {
    if (isAuthenticated && isAuthorized === 'unset') {
      setIsAuthorized('isLoading');
    }
  }, [isAuthenticated, isAuthorized]);

  if (isAuthorized !== 'ok') {
    console.info(isAuthorized);
  }

  if (isAuthorized === 'ok') {
    return <Outlet />;
  } else if (isAuthorized === 'isLoading') {
    return <Loading />;
  } else if (isAuthorized === 'unauthorized') {
    return (
      <Login
        loginFailed={isAuthorized === 'unauthorized'}
        failureMessage={'User unauthorized to act as the SV Party.'}
        title="Super Validator Operations"
        authConfig={authConfig}
        testAuthConfig={testAuthConfig}
      />
    );
  } else {
    return (
      <Login
        title="Super Validator Operations"
        authConfig={authConfig}
        testAuthConfig={testAuthConfig}
      />
    );
  }
};

export default AuthCheck;
