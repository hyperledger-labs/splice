// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading, SvVote } from '@lfdecentralizedtrust/splice-common-frontend';
import { useVotesHooks } from '@lfdecentralizedtrust/splice-common-frontend';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useCallback, useMemo, useState } from 'react';

import CloseIcon from '@mui/icons-material/Close';
import {
  Badge,
  Box,
  Card,
  CardHeader,
  ClickAwayListener,
  IconButton,
  Modal,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import Container from '@mui/material/Container';

import {
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';

import { VoteRequestsFilterTable } from './VoteRequestFilterTable';
import VoteRequestModalContent from './VoteRequestModalContent';
import { VoteResultModalContent } from './VoteResultModalContent';
import { VoteRequestResultTableType, VoteResultsFilterTable } from './VoteResultsFilterTable';

dayjs.extend(utc);

function tabProps(info: string) {
  return {
    id: `information-tab-${info}`,
    'aria-controls': `information-panel-${info}`,
  };
}

interface TabPanelProps {
  children?: React.ReactNode;
  index: number;
  value: number;
}

export function getAction(action: ActionRequiringConfirmation): string {
  if (action.tag === 'ARC_DsoRules') {
    const dsoRulesAction = action.value.dsoAction;
    return `${dsoRulesAction.tag}`;
  } else if (action.tag === 'ARC_AmuletRules') {
    const amuletRulesAction = action.value.amuletRulesAction;
    return `${amuletRulesAction.tag}`;
  } else {
    return 'Action tag not defined.';
  }
}

const TabPanel = (props: TabPanelProps) => {
  const { children, value, index, ...other } = props;
  return (
    <div
      role="tabpanel"
      hidden={value !== index}
      id={`simple-tabpanel-${index}`}
      aria-labelledby={`simple-tab-${index}`}
      {...other}
    >
      {value === index && <Box sx={{ p: 3 }}>{children}</Box>}
    </div>
  );
};

interface ListVoteRequestsProps {
  showActionNeeded: boolean;
  voteForm?: (
    voteRequestContractId: ContractId<VoteRequest>,
    currentSvVote: SvVote | undefined
  ) => React.ReactNode;
}

export type VoteResultModalState =
  | { open: false }
  | {
      open: true;
      tableType: VoteRequestResultTableType;
      voteResult: DsoRules_CloseVoteRequestResult;
      effectiveAt: Date;
    };

export type VoteRequestModalState =
  | { open: false }
  | {
      open: true;
      voteRequestContractId: ContractId<VoteRequest>;
      expiresAt: Date;
      effectiveAt: Date;
    };

export const ListVoteRequests: React.FC<ListVoteRequestsProps> = ({
  showActionNeeded,
  voteForm,
}) => {
  const votesHooks = useVotesHooks();
  const [value, setValue] = React.useState(0);

  const handleChange = (_event: React.SyntheticEvent, newValue: number) => {
    setValue(newValue);
  };

  const listVoteRequestsQuery = votesHooks.useListDsoRulesVoteRequests();

  const voteRequestIds = listVoteRequestsQuery.data
    ? listVoteRequestsQuery.data.map(v => v.payload.trackingCid || v.contractId)
    : [];
  const votesQuery = votesHooks.useListVotes(voteRequestIds);
  const dsoInfosQuery = votesHooks.useDsoInfos();

  const [voteRequestModalState, setVoteRequestModalState] = useState<VoteRequestModalState>({
    open: false,
  });
  const [voteResultModalState, setVoteResultModalState] = useState<VoteResultModalState>({
    open: false,
  });

  const openModalWithVoteRequest = (voteRequestModalState: VoteRequestModalState) => {
    setVoteRequestModalState(voteRequestModalState);
  };

  const openModalWithVoteResult = (voteResultModalState: VoteResultModalState) => {
    setVoteResultModalState(voteResultModalState);
  };

  const handleClose = () => {
    setVoteRequestModalState({ open: false });
    setVoteResultModalState({ open: false });
  };

  const svPartyId = dsoInfosQuery.data?.svPartyId;

  const getMemberName = useCallback(
    (partyId: string) => {
      const member = dsoInfosQuery.data?.dsoRules.payload.svs.get(partyId);
      return member ? member.name : '';
    },
    [dsoInfosQuery.data]
  );

  const alreadyVotedRequestIds: Set<ContractId<VoteRequest>> = useMemo(() => {
    return svPartyId && votesQuery.data
      ? new Set(votesQuery.data.filter(v => v.voter === svPartyId).map(v => v.requestCid))
      : new Set();
  }, [votesQuery.data, svPartyId]);

  if (listVoteRequestsQuery.isLoading || dsoInfosQuery.isLoading || votesQuery.isLoading) {
    return <Loading />;
  }

  if (listVoteRequestsQuery.isError || dsoInfosQuery.isError || votesQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const voteRequestsData = listVoteRequestsQuery.data ? [...listVoteRequestsQuery.data] : [];
  const voteRequests = voteRequestsData.toSorted((a, b) => {
    const createdAtA = a.createdAt;
    const createdAtB = b.createdAt;
    if (createdAtA === createdAtB) {
      return 0;
    } else if (createdAtA < createdAtB) {
      return 1;
    } else {
      return -1;
    }
  });

  const voteRequestsNotVoted = voteRequests.filter(
    v => !alreadyVotedRequestIds.has(v.payload.trackingCid || v.contractId)
  );
  const voteRequestsVoted = voteRequests.filter(v =>
    alreadyVotedRequestIds.has(v.payload.trackingCid || v.contractId)
  );

  const tabsToTabPanel = (
    showActionNeeded
      ? [
          [
            () => (
              <Tab
                key={'action-needed'}
                label="Action Needed"
                {...tabProps('action-needed')}
                id={'tab-panel-action-needed'}
                icon={
                  <Box sx={{ marginLeft: 2 }}>
                    <Badge
                      id="tab-badge-action-needed-count"
                      badgeContent={voteRequestsNotVoted.length}
                      color="error"
                      sx={{ mx: '10px' }}
                    />
                  </Box>
                }
                iconPosition="end"
              />
            ),
            () => (
              <VoteRequestsFilterTable
                voteRequests={voteRequestsNotVoted}
                getAction={getAction}
                openModalWithVoteRequest={openModalWithVoteRequest}
                tableBodyId={'sv-voting-action-needed-table-body'}
              />
            ),
          ],
        ]
      : []
  )
    .concat([
      [
        () => (
          <Tab
            key={'in-progress'}
            label="In Progress"
            {...tabProps('in-progress')}
            id={'tab-panel-in-progress'}
            data-testid={'tab-panel-in-progress'}
          />
        ),
        () => (
          <VoteRequestsFilterTable
            voteRequests={voteRequestsVoted}
            getAction={getAction}
            openModalWithVoteRequest={openModalWithVoteRequest}
            tableBodyId={'sv-voting-in-progress-table-body'}
          />
        ),
      ],
    ])
    .concat([
      [
        () => (
          <Tab
            key={'executed'}
            label="Executed"
            {...tabProps('executed')}
            id={'tab-panel-executed'}
          />
        ),
        () => (
          <VoteResultsFilterTable
            getAction={getAction}
            tableBodyId={'sv-vote-results-executed-table-body'}
            tableType={'Executed'}
            openModalWithVoteResult={openModalWithVoteResult}
            accepted
          />
        ),
      ],
      [
        () => (
          <Tab
            key={'rejected'}
            label="Rejected"
            {...tabProps('rejected')}
            id={'tab-panel-rejected'}
          />
        ),
        () => (
          <VoteResultsFilterTable
            getAction={getAction}
            tableBodyId={'sv-vote-results-rejected-table-body'}
            tableType={'Rejected'}
            openModalWithVoteResult={openModalWithVoteResult}
            validityColumnName={'Rejected At'}
            accepted={false}
          />
        ),
      ],
    ]);

  return (
    <Stack>
      <Typography mt={4} variant="h4">
        Vote Requests
      </Typography>
      <Box mt={4} sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Tabs value={value} onChange={handleChange} aria-label="json tabs">
          {tabsToTabPanel.map(([tab, _]) => tab())}
        </Tabs>
      </Box>
      {tabsToTabPanel.map(([_, tabPanel], index) => (
        <TabPanel value={value} index={index} key={index}>
          {tabPanel()}
        </TabPanel>
      ))}
      <Modal
        open={voteRequestModalState.open}
        onClose={handleClose}
        aria-labelledby="vote-request-modal-title"
        aria-describedby="vote-request-modal-description"
        slotProps={{ root: { id: 'vote-request-modal-root' } }}
      >
        <Box sx={{ flex: 1, overflowY: 'scroll', maxHeight: '100%' }}>
          <ClickAwayListener onClickAway={handleClose}>
            <Container maxWidth="md" sx={{ marginTop: '64px' }}>
              <Card variant="elevation" sx={{ backgroundColor: '#2F2F2F' }}>
                <CardHeader
                  title="Vote Request"
                  action={
                    <IconButton id="vote-request-modal-close-button" onClick={handleClose}>
                      <CloseIcon />
                    </IconButton>
                  }
                />
                {voteRequestModalState.open && (
                  <VoteRequestModalContent
                    voteRequestContractId={voteRequestModalState.voteRequestContractId}
                    handleClose={handleClose}
                    voteForm={voteForm}
                    getMemberName={getMemberName}
                    expiresAt={voteRequestModalState.expiresAt}
                    effectiveAt={voteRequestModalState.effectiveAt}
                  />
                )}
              </Card>
            </Container>
          </ClickAwayListener>
        </Box>
      </Modal>
      <Modal
        open={voteResultModalState.open}
        onClose={handleClose}
        aria-labelledby="vote-result-modal-title"
        aria-describedby="vote-result-modal-description"
        slotProps={{ root: { id: 'vote-result-modal-root' } }}
      >
        <Box sx={{ flex: 1, overflowY: 'scroll', maxHeight: '100%' }}>
          <ClickAwayListener onClickAway={handleClose}>
            <Container maxWidth="md" sx={{ marginTop: '64px' }}>
              <Card variant="elevation" sx={{ backgroundColor: '#2F2F2F' }}>
                <CardHeader
                  title="Vote Result"
                  action={
                    <IconButton id="vote-result-modal-close-button" onClick={handleClose}>
                      <CloseIcon />
                    </IconButton>
                  }
                />
                <VoteResultModalContent
                  voteResultModalState={voteResultModalState}
                  getMemberName={getMemberName}
                />
              </Card>
            </Container>
          </ClickAwayListener>
        </Box>
      </Modal>
    </Stack>
  );
};
