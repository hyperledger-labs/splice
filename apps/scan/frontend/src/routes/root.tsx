import * as React from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';

import { Box, Grid, Stack, Tab, Tabs } from '@mui/material';

import NetworkInfo from '../components/NetworkInfo';

const Root: React.FC = () => {
  const navLinks = [
    { name: 'Recent Activity', path: 'recent-activity' },
    { name: 'App Leaderboard', path: 'app-leaderboard' },
    { name: 'Validator Leaderboard', path: 'validator-leaderboard' },
  ];
  // Unfortunately, NavLink from react-router-dom doesn't realize that 'recent-activity' is the index at '/',
  // so we need to set it as active manually.
  const currentPath = useLocation().pathname;
  const selected = navLinks.find(({ path }) => currentPath.includes(path)) || navLinks[0];

  return (
    <Box display="flex" flexDirection="column" minHeight="100vh" bgcolor="colors.neutral.25">
      <Grid container spacing={4}>
        <Grid item xs={12} md>
          <Stack spacing={4}>
            <Tabs value={selected}>
              {navLinks.map(navLink => {
                return (
                  <Tab
                    key={navLink.path}
                    to={navLink.path}
                    label={navLink.name}
                    value={navLink}
                    component={NavLink}
                  />
                );
              })}
            </Tabs>
            <Outlet />
          </Stack>
        </Grid>
        <Grid item xs={12} md="auto">
          <NetworkInfo />
        </Grid>
      </Grid>
    </Box>
  );
};

export default Root;
