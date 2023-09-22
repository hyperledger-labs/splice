import * as React from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';

import { Grid, Tab, Tabs, Typography, Box } from '@mui/material';

import Header from '../components/Header';
import NetworkInfo from '../components/NetworkInfo';
import TotalCoinBalance from '../components/TotalCoinBalance';
import TotalRewards from '../components/TotalRewards';

const Root: React.FC = () => {
  const navLinks = [
    { name: 'Recent Activity', path: 'activity' },
    { name: 'App Leaderboard', path: 'app-leaderboard' },
    { name: 'Validator Leaderboard', path: 'validator-leaderboard' },
    { name: 'Domain Fees Leaderboard', path: 'domain-fees-leaderboard' },
  ];
  // Unfortunately, NavLink from react-router-dom doesn't realize that 'activity' is the index at '/',
  // so we need to set it as active manually.
  const currentPath = useLocation().pathname;
  const selected = navLinks.find(({ path }) => currentPath.includes(path)) || navLinks[0];

  return (
    <Grid container margin={4} pr={4} spacing={4} justifyContent="center" sx={{ width: 'auto' }}>
      <Grid item xs={12}>
        <Header />
      </Grid>

      <Grid item xs={12}>
        <Typography variant="h5">
          Explore, search and find answers to current network configuration details.
        </Typography>
      </Grid>

      <Grid item xs={12} lg={6}>
        <TotalCoinBalance />
      </Grid>

      <Grid item xs={12} lg={6}>
        <TotalRewards />
      </Grid>

      <Grid item xs={12} lg={6}>
        <Box mb={0}>
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
        </Box>
        <Outlet />
      </Grid>

      {/** spacer element to separate NetworkInfo from tables on large screen widths */}
      <Grid item xs={0} lg={1} />

      <Grid item xs={12} lg={5}>
        <NetworkInfo />
      </Grid>
    </Grid>
  );
};

export default Root;
