import {
  Contract,
  CopyableTypography,
  DateDisplay,
  Loading,
  SvClientProvider,
} from 'common-frontend';
import React, { useMemo, useState } from 'react';

import { ClickAwayListener } from '@mui/base';
import CheckIcon from '@mui/icons-material/Check';
import ClearIcon from '@mui/icons-material/Clear';
import CloseIcon from '@mui/icons-material/Close';
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown';
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp';
import {
  Box,
  Button,
  Card,
  CardHeader,
  Collapse,
  IconButton,
  Modal,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import Container from '@mui/material/Container';
import Link from '@mui/material/Link';

import {
  ActionRequiringConfirmation,
  SvcRules,
  VoteResult,
} from '@daml.js/svc-governance/lib/CN/SvcRules';
import { ContractId } from '@daml/types';

import { VoteRequest } from '../../../../../common/frontend/daml.js/svc-governance-0.1.0/lib/CN/SvcRules';
import { useSvcInfos } from '../../contexts/SvContext';
import {
  useListSvcRulesVoteRequests,
  useListSvcRulesVoteResults,
} from '../../hooks/useListVoteRequests';
import { useListVotes } from '../../hooks/useListVotes';
import { config } from '../../utils';
import VoteRequestModalContent from './VoteRequestModalContent';
import ActionView from './actions/views/ActionView';

const dayjs = require('dayjs');
const utc = require('dayjs/plugin/utc');
dayjs.extend(utc);

const ListVoteRequests: React.FC = () => {
  const listVoteRequestsQuery = useListSvcRulesVoteRequests();

  const listVoteResultsExecuted = useListSvcRulesVoteResults(
    {
      executed: true,
      effectiveTo: dayjs.utc().format('YYYY-MM-DDTHH:mm:ss[Z]'),
    },
    10
  );
  const listVoteResultsPlanned = useListSvcRulesVoteResults(
    {
      executed: true,
      effectiveFrom: dayjs.utc().format('YYYY-MM-DDTHH:mm:ss[Z]'),
    },
    10
  );
  const listVoteResultsRejected = useListSvcRulesVoteResults(
    {
      executed: false,
    },
    10
  );

  const voteRequestIds = listVoteRequestsQuery.data
    ? listVoteRequestsQuery.data.map(v => v.contractId)
    : [];
  const votesQuery = useListVotes(voteRequestIds);
  const svcInfosQuery = useSvcInfos();

  const [voteRequestContractId, setVoteRequestContractId] = useState<
    ContractId<VoteRequest> | undefined
  >(undefined);
  const [isVoteRequestModalOpen, setVoteRequestModalOpen] = useState<boolean>(false);
  const [staledVoteRequestModal, setStaledVoteRequestModal] = useState<boolean>(false);

  const openModalWithVoteRequest = (
    voteRequestContractId: ContractId<VoteRequest>,
    staled: boolean
  ) => {
    setVoteRequestContractId(voteRequestContractId);
    setStaledVoteRequestModal(staled);
    setVoteRequestModalOpen(true);
  };

  const handleClose = () => setVoteRequestModalOpen(false);

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
      .filter(([requestCid, totalVotes]) => totalVotes >= votingThreshold)
      .map(([Cid, _]) => Cid);
  }, [votesQuery.data, votingThreshold]);

  if (
    listVoteRequestsQuery.isLoading ||
    svcInfosQuery.isLoading ||
    votesQuery.isLoading ||
    listVoteResultsExecuted.isLoading ||
    listVoteResultsPlanned.isLoading ||
    listVoteResultsRejected.isLoading
  ) {
    return <Loading />;
  }

  if (
    listVoteRequestsQuery.isError ||
    svcInfosQuery.isError ||
    votesQuery.isError ||
    listVoteResultsExecuted.isError ||
    listVoteResultsPlanned.isError ||
    listVoteResultsRejected.isError
  ) {
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
          return `${svcRulesAction.tag} : ${svcInfosQuery.data!.svcRules.payload.members.get(
            svcRulesAction.value.member
          )?.name!}`;
        }
        case 'SRARC_GrantFeaturedAppRight': {
          return `${svcRulesAction.tag} : ${svcRulesAction.value.provider}`;
        }
        case 'SRARC_RevokeFeaturedAppRight': {
          return `${svcRulesAction.tag} : ${svcRulesAction.value.rightCid}`;
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
  // TODO(#7613): remove isDevNet flag and add feature in release_notes
  return (
    <>
      <Typography mt={6} variant="h4">
        Vote Requests
      </Typography>
      <Typography mt={6} variant="h5">
        Action Needed
      </Typography>
      <ListVoteRequestsTable
        voteRequests={voteRequestsNotVoted}
        getAction={getAction}
        svcRules={svcInfosQuery.data!.svcRules}
        openModalWithVoteRequest={openModalWithVoteRequest}
        tableBodyId={'sv-voting-action-needed-table-body'}
        staled={false}
      />
      <Typography mt={6} variant="h5">
        In Progress
      </Typography>
      <ListVoteRequestsTable
        voteRequests={voteRequestsVoted}
        getAction={getAction}
        svcRules={svcInfosQuery.data!.svcRules}
        openModalWithVoteRequest={openModalWithVoteRequest}
        tableBodyId={'sv-voting-in-progress-table-body'}
        staled={false}
      />
      {svcInfosQuery.data?.svcRules.payload.isDevNet && (
        <Stack>
          <Typography mt={6} variant="h5">
            Planned
          </Typography>
          <ListVoteResultsTable
            voteResults={listVoteResultsPlanned.data! || []}
            getAction={getAction}
            tableBodyId={'sv-vote-results-planned-table-body'}
          />
          <Typography mt={6} variant="h5">
            Executed
          </Typography>
          <ListVoteResultsTable
            voteResults={listVoteResultsExecuted.data! || []}
            getAction={getAction}
            tableBodyId={'sv-vote-results-executed-table-body'}
          />
          <Typography mt={6} variant="h5">
            Rejected
          </Typography>
          <ListVoteResultsTable
            voteResults={listVoteResultsRejected.data! || []}
            getAction={getAction}
            tableBodyId={'sv-vote-results-executed-table-body'}
          />
        </Stack>
      )}
      {voteRequestsStaled.length > 0 && (
        <Stack>
          <Typography mt={6} variant="h5">
            Rejected due to system conflict
          </Typography>
          <ListVoteRequestsTable
            voteRequests={voteRequestsStaled}
            getAction={getAction}
            svcRules={svcInfosQuery.data!.svcRules}
            openModalWithVoteRequest={openModalWithVoteRequest}
            tableBodyId={'sv-voting-staled-table-body'}
            staled
          />
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
    </>
  );
};

interface VoteRequestsRowProps {
  contractId: ContractId<VoteRequest>;
  action: string;
  requester: string;
  expires: Date;
  idx: number;
  description: string;
  url: string;
  openModalWithVoteRequest: (contractId: ContractId<VoteRequest>, staled: boolean) => void;
  staled: boolean;
}

const VoteRequestRow: React.FC<VoteRequestsRowProps> = ({
  contractId,
  action,
  requester,
  expires,
  idx,
  description,
  url,
  openModalWithVoteRequest,
  staled,
}) => {
  const [open, setOpen] = useState(-1);
  const votesQuery = useListVotes(contractId ? [contractId] : []);

  const allVotes = votesQuery.data
    ? votesQuery.data.sort((a, b) => {
        return b.expiresAt.valueOf() - a.expiresAt.valueOf();
      })
    : [];

  const acceptedVotes = allVotes.filter(v => v.accept);
  const rejectedVotes = allVotes.filter(v => !v.accept);

  return (
    <>
      <TableRow key={idx} className="vote-request-row">
        <TableCell>
          <IconButton
            aria-label="expand row"
            size="small"
            onClick={() => setOpen(open === idx ? -1 : idx)}
          >
            {open === idx ? <KeyboardArrowUpIcon /> : <KeyboardArrowDownIcon />}
          </IconButton>
        </TableCell>
        <TableCell className="vote-row-action">
          <CopyableTypography text={action} maxWidth={'300px'} />
        </TableCell>
        <TableCell className="vote-row-requester">{requester}</TableCell>
        <TableCell className="vote-row-vote-status" color="default">
          <Stack spacing={4} direction="row">
            <Typography id={'vote-request-modal-rejected-count-' + idx} variant="h6">
              <ClearIcon color="error" fontSize="inherit" /> {rejectedVotes.length}
            </Typography>
            <Typography id={'vote-request-modal-accepted-count-' + idx} variant="h6">
              <CheckIcon color="success" fontSize="inherit" /> {acceptedVotes.length}
            </Typography>
          </Stack>
        </TableCell>
        <TableCell>
          <DateDisplay datetime={expires.toISOString()} />
        </TableCell>
        <TableCell className="vote-row-review">
          <Button
            size="small"
            variant="contained"
            color="primary"
            onClick={() => openModalWithVoteRequest(contractId, staled)}
            className="vote-row-review-button"
          >
            Review
          </Button>
        </TableCell>
      </TableRow>
      <TableRow>
        <TableCell colSpan={5} sx={{ paddingBottom: 0, paddingTop: 0, border: '0px' }}>
          <Collapse in={open === idx} timeout="auto" unmountOnExit>
            <Box
              sx={{
                width: '100%',
                backgroundColor: 'rgba(50,50,50,0.4)',
                minHeight: 36,
                textAlign: 'center',
                alignItems: 'center',
                fontSize: 18,
              }}
            >
              <TableContainer>
                <Table
                  style={{ tableLayout: 'fixed' }}
                  size="small"
                  className="sv-sub-voting-table"
                >
                  <TableBody>
                    <TableCell>
                      <span style={{ fontWeight: 'bold' }}>Summary:</span> {description}
                    </TableCell>
                    <TableCell>
                      <span style={{ fontWeight: 'bold' }}>URL:</span> <Link href={url}>{url}</Link>
                    </TableCell>
                  </TableBody>
                </Table>
              </TableContainer>
            </Box>
          </Collapse>
        </TableCell>
      </TableRow>
    </>
  );
};

interface ListVoteRequestsTableProps {
  voteRequests: Contract<VoteRequest>[];
  getAction: (action: ActionRequiringConfirmation, staled: boolean) => string;
  svcRules: Contract<SvcRules>;
  openModalWithVoteRequest: (
    voteRequestContractId: ContractId<VoteRequest>,
    staled: boolean
  ) => void;
  tableBodyId: string;
  staled: boolean;
}

const ListVoteRequestsTable: React.FC<ListVoteRequestsTableProps> = ({
  voteRequests,
  getAction,
  svcRules,
  openModalWithVoteRequest,
  tableBodyId,
  staled,
}) => {
  return (
    <TableContainer>
      <Table style={{ tableLayout: 'auto' }} id="sv-voting-action-needed-table">
        <TableHead>
          <TableRow>
            <TableCell></TableCell>
            <TableCell>Action</TableCell>
            <TableCell>Requester</TableCell>
            <TableCell>Vote Status</TableCell>
            <TableCell>Expires At</TableCell>
            <TableCell></TableCell>
          </TableRow>
        </TableHead>
        <TableBody id={tableBodyId}>
          {voteRequests.map((request, index) => {
            return (
              <VoteRequestRow
                key={request.contractId}
                contractId={request.contractId}
                action={getAction(request.payload.action, staled)}
                requester={svcRules.payload.members.get(request.payload.requester)?.name!}
                expires={new Date(request.payload.expiresAt)}
                idx={index}
                description={request.payload.reason.body}
                url={request.payload.reason.url}
                openModalWithVoteRequest={openModalWithVoteRequest}
                staled={staled}
              />
            );
          })}
        </TableBody>
      </Table>
    </TableContainer>
  );
};

interface VoteResultRowProps {
  action: string;
  voteResult: VoteResult;
  idx: number;
}

const VoteResultRow: React.FC<VoteResultRowProps> = ({ action, voteResult, idx }) => {
  const [open, setOpen] = useState(-1);

  return (
    <>
      <TableRow key={idx} className="vote-result-row">
        <TableCell>
          <IconButton
            aria-label="expand row"
            size="small"
            onClick={() => setOpen(open === idx ? -1 : idx)}
          >
            {open === idx ? <KeyboardArrowUpIcon /> : <KeyboardArrowDownIcon />}
          </IconButton>
        </TableCell>
        <TableCell className="vote-row-action">
          <CopyableTypography text={action} maxWidth={'300px'} />
        </TableCell>
        <TableCell className="vote-row-requester">
          <CopyableTypography text={voteResult.requester} maxWidth={'300px'} />
        </TableCell>
        <TableCell className="vote-row-vote-status" color="default">
          <Stack spacing={4} direction="row">
            <Typography id={'vote-result-rejected-count-' + idx} variant="h6">
              <ClearIcon color="error" fontSize="inherit" /> {voteResult.rejectedBy.length}
            </Typography>
            <Typography id={'vote-result-accepted-count-' + idx} variant="h6">
              <CheckIcon color="success" fontSize="inherit" /> {voteResult.acceptedBy.length}
            </Typography>
          </Stack>
        </TableCell>
        <TableCell>
          <DateDisplay datetime={new Date(voteResult.effectiveAt).toLocaleString()} />
        </TableCell>
      </TableRow>
      <TableRow>
        <TableCell colSpan={5} sx={{ paddingBottom: 0, paddingTop: 0, border: '0px' }}>
          <Collapse in={open === idx} timeout="auto" unmountOnExit>
            <Box
              sx={{
                width: '100%',
                backgroundColor: 'rgba(50,50,50,0.4)',
                minHeight: 36,
                textAlign: 'center',
                alignItems: 'center',
                fontSize: 18,
              }}
            >
              <TableContainer>
                <Table
                  style={{ tableLayout: 'fixed' }}
                  size="small"
                  className="sv-sub-result-table"
                >
                  <TableBody>
                    <TableRow>
                      <ActionView action={voteResult.action} />
                    </TableRow>
                  </TableBody>
                </Table>
              </TableContainer>
            </Box>
          </Collapse>
        </TableCell>
      </TableRow>
    </>
  );
};

interface ListVoteResultsTableProps {
  voteResults: VoteResult[];
  getAction: (action: ActionRequiringConfirmation, staled: boolean) => string;
  tableBodyId: string;
}

const ListVoteResultsTable: React.FC<ListVoteResultsTableProps> = ({
  voteResults,
  getAction,
  tableBodyId,
}) => {
  return (
    <TableContainer>
      <Table style={{ tableLayout: 'auto' }} id="sv-voting-action-needed-table">
        <TableHead>
          <TableRow>
            <TableCell></TableCell>
            <TableCell>Action</TableCell>
            <TableCell>Requester</TableCell>
            <TableCell>Vote Status</TableCell>
            <TableCell>Effective At</TableCell>
            <TableCell></TableCell>
          </TableRow>
        </TableHead>
        <TableBody id={tableBodyId}>
          {voteResults.map((result, index) => {
            return (
              <VoteResultRow
                key={index}
                action={getAction(result.action, false)}
                voteResult={result}
                idx={index}
              />
            );
          })}
        </TableBody>
      </Table>
    </TableContainer>
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
