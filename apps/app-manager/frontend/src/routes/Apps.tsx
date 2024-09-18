import React from 'react';

import { Stack } from '@mui/material';

import InstalledApps from '../components/InstalledApps';
import RegisteredApps from '../components/RegisteredApps';

const Apps: React.FC = () => {
  return (
    <Stack>
      <RegisteredApps />
      <InstalledApps />
    </Stack>
  );
};

export default Apps;
