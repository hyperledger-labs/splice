// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Copyright } from 'common-frontend';
import React from 'react';
import { Outlet } from 'react-router-dom';

import Layout from '../components/Layout';

const Root: React.FC = () => {
  return (
    <Layout>
      <Outlet />
      <Copyright />
    </Layout>
  );
};
export default Root;
