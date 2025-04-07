// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Header, useUserState, useVotesHooks } from '@lfdecentralizedtrust/splice-common-frontend';

import { Logout } from '@mui/icons-material';
import { Box, Button, Divider, Stack, Typography } from '@mui/material';
import Container from '@mui/material/Container';
import Link from '@mui/material/Link';

import { useElectionContext } from '../contexts/SvContext';
import { useNetworkInstanceName } from '../hooks/index';
import { useSvConfig } from '../utils';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  const config = useSvConfig();
  const { logout } = useUserState();
  const networkInstanceName = useNetworkInstanceName();
  const networkInstanceNameColor = `colors.${networkInstanceName?.toLowerCase()}`;

  const votesHooks = useVotesHooks();
  const dsoInfosQuery = votesHooks.useDsoInfos();
  const listVoteRequestsQuery = votesHooks.useListDsoRulesVoteRequests();
  const svPartyId = dsoInfosQuery.data?.svPartyId;
  const actionsPending = listVoteRequestsQuery.data?.filter(
    vr => vr.payload.votes.entriesArray().find(e => e[1].sv === svPartyId) === undefined
  );
  const electionContextQuery = useElectionContext();
  const hasElectionRequest = (electionContextQuery?.data?.ranking?.length ?? 0) > 0;

  return (
    <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" minHeight="100vh">
      {networkInstanceName === undefined ? (
        <></>
      ) : (
        <Stack
          direction="row"
          alignItems="center"
          justifyContent="center"
          sx={{
            position: 'sticky',
            top: 0,
            zIndex: 1100,
            backgroundColor: `${networkInstanceNameColor}`,
            color: 'black',
            height: '50px',
            width: '100%',
          }}
        >
          <Typography id="network-instance-name" data-testid="network-instance-name" variant="h6">
            <b>You are on {networkInstanceName} </b>
          </Typography>
        </Stack>
      )}
      <Container maxWidth="xl">
        <Header
          title="Super Validator Operations"
          navLinks={[
            { name: 'Information', path: 'dso' },
            { name: 'Validator Onboarding', path: 'validator-onboarding' },
            { name: `${config.spliceInstanceNames.amuletName} Price`, path: 'amulet-price' },
            { name: 'Delegate Election', path: 'delegate', hasAlert: hasElectionRequest },
            { name: 'Governance', path: 'votes', badgeCount: actionsPending?.length },
          ]}
        >
          <Stack direction="row" alignItems="center" spacing={1} sx={{ flexShrink: 0 }}>
            <Divider key="divider" orientation="vertical" variant="middle" flexItem />
            <Button key="button" id="logout-button" onClick={logout} color="inherit">
              <Stack direction="row" alignItems="center">
                <Logout />
                <Link color="inherit" textTransform="none">
                  Logout
                </Link>
              </Stack>
            </Button>
          </Stack>
        </Header>
      </Container>

      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg">{props.children}</Container>
      </Box>
    </Box>
  );
};

export default Layout;
