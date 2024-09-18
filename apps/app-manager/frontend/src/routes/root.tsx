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
