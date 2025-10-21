// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useGetRoundOfLatestData } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import React, { useMemo } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';

import { GridLegacy, Tab, Tabs, Typography, Box } from '@mui/material';

import Layout from '../components/Layout';
import NetworkInfo from '../components/NetworkInfo';
import TotalAmuletBalance from '../components/TotalAmuletBalance';
import TotalRewards from '../components/TotalRewards';

const Root: React.FC = () => {
  const navLinks = [
    { name: 'Recent Activity', path: 'recent-activity' },
    { name: 'App Leaderboard', path: 'app-leaderboard' },
    { name: 'Validator Leaderboard', path: 'validator-leaderboard' },
    { name: 'Synchronizer Fees Leaderboard', path: 'synchronizer-fees-leaderboard' },
    { name: 'Validator Liveness Leaderboard', path: 'validator-faucets-leaderboard' },
  ];
  // Unfortunately, NavLink from react-router-dom doesn't realize that 'recent-activity' is the index at '/',
  // so we need to set it as active manually.
  const currentPath = useLocation().pathname;
  const selected = navLinks.find(({ path }) => currentPath.includes(path)) || navLinks[0];

  const { data: latestRound, error } = useGetRoundOfLatestData();

  const round = useMemo(() => {
    if (error) {
      if (
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (error as any).code === 404 &&
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (error as any).body.error === 'No data has been made available yet'
      ) {
        // Backend is working, but no round data is available
        return '??';
      } else {
        return '--';
      }
    }
    return latestRound?.round !== undefined ? latestRound.round : '--';
  }, [latestRound, error]);

  return (
    <Layout>
      <GridLegacy
        container
        margin={4}
        pr={4}
        spacing={4}
        justifyContent="center"
        sx={{ width: 'auto' }}
      >
        <GridLegacy item xs={8}>
          <Typography variant="h5">
            Explore, search and find answers to current network configuration details.
          </Typography>
        </GridLegacy>

        <GridLegacy item xs={4}>
          <div id="as-of-round">
            <Typography variant="body2" data-testid="round-of-latest-data-text">
              The content on this page is computed as of round:{' '}
              <span data-testid="round-of-latest-data-value">{round}</span>
            </Typography>
          </div>
        </GridLegacy>

        <GridLegacy item xs={12} lg={6} data-testid="circulating-supply-container">
          <TotalAmuletBalance />
        </GridLegacy>

        <GridLegacy item xs={12} lg={6}>
          <TotalRewards />
        </GridLegacy>

        <GridLegacy item xs={12} lg={6}>
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
        </GridLegacy>

        {/** spacer element to separate NetworkInfo from tables on large screen widths */}
        <GridLegacy item xs={0} lg={1} />

        <GridLegacy item xs={12} lg={5}>
          <NetworkInfo />
        </GridLegacy>
      </GridLegacy>
    </Layout>
  );
};

export default Root;
