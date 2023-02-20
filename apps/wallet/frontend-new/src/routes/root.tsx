import React from 'react';
import { Outlet } from 'react-router-dom';

import Copyright from '../components/Copyright';
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
