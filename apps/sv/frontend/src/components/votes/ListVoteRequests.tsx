import { Loading, SvClientProvider } from 'common-frontend';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useEffect, useMemo, useState } from 'react';

import { ClickAwayListener } from '@mui/base';
import CloseIcon from '@mui/icons-material/Close';
import {
  Box,
  Card,
  CardHeader,
  IconButton,
  Modal,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import Container from '@mui/material/Container';

import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules';
import { ContractId } from '@daml/types';

import { VoteRequest } from '../../../../../common/frontend/daml.js/svc-governance-0.1.0/lib/CN/SvcRules';
import { useSvcInfos } from '../../contexts/SvContext';
import { useListSvcRulesVoteRequests } from '../../hooks/useListVoteRequests';
import { useListVotes } from '../../hooks/useListVotes';
import { config } from '../../utils';
import { ListVoteRequestsFilterTable } from './VoteRequestFilterTable';
import VoteRequestModalContent from './VoteRequestModalContent';
import { VoteResultModalContent } from './VoteResultModalContent';
import { VoteResultsFilterTable } from './VoteResultsFilterTable';

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

const ListVoteRequests: React.FC = () => {
  const [value, setValue] = React.useState(0);
  const [now, setNow] = useState<string>(dayjs().utc().format('YYYY-MM-DDTHH:mm:ss[Z]'));

  useEffect(() => {
    const interval = setInterval(() => {
      setNow(dayjs().utc().format('YYYY-MM-DDTHH:mm:ss[Z]'));
    }, 500);

    return () => clearInterval(interval);
  }, []);

  const handleChange = (_event: React.SyntheticEvent, newValue: number) => {
    setValue(newValue);
  };

  const listVoteRequestsQuery = useListSvcRulesVoteRequests();

  const voteRequestIds = listVoteRequestsQuery.data
    ? listVoteRequestsQuery.data.map(v => v.contractId)
    : [];
  const votesQuery = useListVotes(voteRequestIds);
  const svcInfosQuery = useSvcInfos();

  const [voteRequestContractId, setVoteRequestContractId] = useState<
    ContractId<VoteRequest> | undefined
  >(undefined);
  const [action, setAction] = useState<ActionRequiringConfirmation | undefined>(undefined);
  const [isVoteRequestModalOpen, setVoteRequestModalOpen] = useState<boolean>(false);
  const [isVoteResultModalOpen, setVoteResultModalOpen] = useState<boolean>(false);
  const [staledVoteRequestModal, setStaledVoteRequestModal] = useState<boolean>(false);

  const openModalWithVoteRequest = (
    voteRequestContractId: ContractId<VoteRequest>,
    staled: boolean
  ) => {
    setVoteRequestContractId(voteRequestContractId);
    setStaledVoteRequestModal(staled);
    setVoteRequestModalOpen(true);
  };

  const openModalWithVoteResult = (action: ActionRequiringConfirmation) => {
    setAction(action);
    setVoteResultModalOpen(true);
  };

  const handleClose = () => {
    setVoteRequestModalOpen(false);
    setVoteResultModalOpen(false);
  };

  const svPartyId = svcInfosQuery.data?.svPartyId;
  const votingThreshold = svcInfosQuery.data?.votingThreshold;

  const alreadyVotedRequestIds = useMemo(() => {
    return svPartyId && votesQuery.data
      ? new Set(votesQuery.data.filter(v => v.voter === svPartyId).map(v => v.requestCid))
      : new Set();
  }, [votesQuery.data, svPartyId]);

  const staledVotedRequestsIds = useMemo(() => {
    if (!votesQuery.data || !votingThreshold) {
      return [];
    }
    const groupedVotes = votesQuery.data.reduce((dic: { [key: string]: number }, vote) => {
      if (vote.accept) {
        dic[vote.requestCid] = (dic[vote.requestCid] || 0) + 1;
      }
      return dic;
    }, {});
    return Object.entries(groupedVotes)
      .filter(([, totalVotes]) => totalVotes >= votingThreshold)
      .map(([Cid, _]) => Cid);
  }, [votesQuery.data, votingThreshold]);

  if (listVoteRequestsQuery.isLoading || svcInfosQuery.isLoading || votesQuery.isLoading) {
    return <Loading />;
  }

  if (listVoteRequestsQuery.isError || svcInfosQuery.isError || votesQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const voteRequests = listVoteRequestsQuery.data.sort((a, b) => {
    const createdAtA = a.metadata.createdAt;
    const createdAtB = b.metadata.createdAt;
    if (createdAtA === createdAtB) {
      return 0;
    } else if (createdAtA < createdAtB) {
      return 1;
    } else {
      return -1;
    }
  });

  const voteRequestsNotVoted = voteRequests.filter(
    v => !alreadyVotedRequestIds.has(v.contractId) && !staledVotedRequestsIds.includes(v.contractId)
  );
  const voteRequestsVoted = voteRequests.filter(
    v => alreadyVotedRequestIds.has(v.contractId) && !staledVotedRequestsIds.includes(v.contractId)
  );
  const voteRequestsStaled = voteRequests.filter(v =>
    staledVotedRequestsIds.includes(v.contractId)
  );

  function getAction(action: ActionRequiringConfirmation) {
    if (action.tag === 'ARC_SvcRules') {
      const svcRulesAction = action.value.svcAction;
      switch (svcRulesAction.tag) {
        case 'SRARC_RemoveMember': {
          return `${svcRulesAction.tag}`;
        }
        case 'SRARC_GrantFeaturedAppRight': {
          return `${svcRulesAction.tag}`;
        }
        case 'SRARC_RevokeFeaturedAppRight': {
          return `${svcRulesAction.tag}`;
        }
        case 'SRARC_SetConfig': {
          return `${svcRulesAction.tag}`;
        }
      }
    } else if (action.tag === 'ARC_CoinRules') {
      const coinRulesAction = action.value.coinRulesAction;
      switch (coinRulesAction.tag) {
        default: {
          return `${coinRulesAction.tag}`;
        }
      }
    }
    return 'Action tag not defined.';
  }

  return (
    <Stack>
      <Typography mt={4} variant="h4">
        Vote Requests
      </Typography>
      <Box mt={4} sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Tabs value={value} onChange={handleChange} aria-label="json tabs">
          <Tab
            label="Action Needed"
            {...tabProps('action-needed')}
            id={'tab-panel-action-needed'}
          />
          <Tab label="In Progress" {...tabProps('in-progress')} id={'tab-panel-in-progress'} />
          <Tab label="Planned" {...tabProps('planned')} id={'tab-panel-planned'} />
          <Tab label="Executed" {...tabProps('executed')} id={'tab-panel-executed'} />
          <Tab label="Rejected" {...tabProps('rejected')} id={'tab-panel-rejected'} />
          {voteRequestsStaled.length > 0 && (
            <Tab label="Aborted" {...tabProps('aborted')} id={'tab-panel-aborted'} />
          )}
        </Tabs>
      </Box>
      <TabPanel value={value} index={0}>
        <ListVoteRequestsFilterTable
          voteRequests={voteRequestsNotVoted}
          getAction={getAction}
          svcRules={svcInfosQuery.data!.svcRules}
          openModalWithVoteRequest={openModalWithVoteRequest}
          tableBodyId={'sv-voting-action-needed-table-body'}
          staled={false}
        />
      </TabPanel>
      <TabPanel value={value} index={1}>
        <ListVoteRequestsFilterTable
          voteRequests={voteRequestsVoted}
          getAction={getAction}
          svcRules={svcInfosQuery.data!.svcRules}
          openModalWithVoteRequest={openModalWithVoteRequest}
          tableBodyId={'sv-voting-in-progress-table-body'}
          staled={false}
        />
      </TabPanel>
      <TabPanel value={value} index={2}>
        <VoteResultsFilterTable
          getAction={getAction}
          tableBodyId={'sv-vote-results-planned-table-body'}
          openModalWithVoteResult={openModalWithVoteResult}
          validityColumnName={'Effective At'}
          executed
          effectiveFrom={now}
        />
      </TabPanel>
      <TabPanel value={value} index={3}>
        <VoteResultsFilterTable
          getAction={getAction}
          tableBodyId={'sv-vote-results-executed-table-body'}
          openModalWithVoteResult={openModalWithVoteResult}
          executed
          effectiveTo={now}
        />
      </TabPanel>
      <TabPanel value={value} index={4}>
        <VoteResultsFilterTable
          getAction={getAction}
          tableBodyId={'sv-vote-results-rejected-table-body'}
          openModalWithVoteResult={openModalWithVoteResult}
          validityColumnName={'Not Executed At'}
          executed={false}
        />
      </TabPanel>
      {voteRequestsStaled.length > 0 && (
        <Stack>
          <TabPanel value={value} index={5}>
            <ListVoteRequestsFilterTable
              voteRequests={voteRequestsStaled}
              getAction={getAction}
              svcRules={svcInfosQuery.data!.svcRules}
              openModalWithVoteRequest={openModalWithVoteRequest}
              tableBodyId={'sv-voting-staled-table-body'}
              staled
            />
          </TabPanel>
        </Stack>
      )}
      <Modal
        open={isVoteRequestModalOpen}
        onClose={handleClose}
        aria-labelledby="voterequest-modal-title"
        aria-describedby="voterequest-modal-description"
      >
        <Box sx={{ flex: 1, overflowY: 'scroll', maxHeight: '100%' }}>
          <ClickAwayListener onClickAway={handleClose}>
            <Container maxWidth="md" sx={{ marginTop: '64px' }}>
              <Card variant="elevation" sx={{ backgroundColor: '#2F2F2F' }}>
                <CardHeader
                  title="Vote Request"
                  action={
                    <IconButton onClick={handleClose}>
                      <CloseIcon />
                    </IconButton>
                  }
                />
                <VoteRequestModalContent
                  voteRequestContractId={voteRequestContractId}
                  handleClose={handleClose}
                  staled={staledVoteRequestModal}
                />
              </Card>
            </Container>
          </ClickAwayListener>
        </Box>
      </Modal>
      <Modal
        open={isVoteResultModalOpen}
        onClose={handleClose}
        aria-labelledby="voteresult-modal-title"
        aria-describedby="voteresult-modal-description"
      >
        <Box sx={{ flex: 1, overflowY: 'scroll', maxHeight: '100%' }}>
          <ClickAwayListener onClickAway={handleClose}>
            <Container maxWidth="md" sx={{ marginTop: '64px' }}>
              <Card variant="elevation" sx={{ backgroundColor: '#2F2F2F' }}>
                <CardHeader
                  title="Vote Result"
                  action={
                    <IconButton onClick={handleClose}>
                      <CloseIcon />
                    </IconButton>
                  }
                />
                <VoteResultModalContent action={action} />
              </Card>
            </Container>
          </ClickAwayListener>
        </Box>
      </Modal>
    </Stack>
  );
};

const ListVoteRequestsWithContexts: React.FC = () => {
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ListVoteRequests />
    </SvClientProvider>
  );
};

export default ListVoteRequestsWithContexts;
