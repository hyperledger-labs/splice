import { CopyableTypography, DateDisplay, Loading, SvClientProvider } from 'common-frontend';
import { Contract } from 'common-frontend';
import React, { useMemo, useState } from 'react';

import { ClickAwayListener } from '@mui/base';
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

import { ActionRequiringConfirmation, SvcRules } from '@daml.js/svc-governance/lib/CN/SvcRules';
import { ContractId } from '@daml/types';

import { VoteRequest } from '../../../../../common/frontend/daml.js/svc-governance-0.1.0/lib/CN/SvcRules';
import { useSvcInfos } from '../../contexts/SvContext';
import { useListSvcRulesVoteRequests } from '../../hooks/useListVoteRequests';
import { useListVotes } from '../../hooks/useListVotes';
import { config } from '../../utils';
import VoteRequestModalContent from './VoteRequestModalContent';

const ListVoteRequests: React.FC = () => {
  const listVoteRequestsQuery = useListSvcRulesVoteRequests();
  const voteRequestIds = listVoteRequestsQuery.data
    ? listVoteRequestsQuery.data.map(v => v.contractId)
    : [];
  const votesQuery = useListVotes(voteRequestIds);

  const svcInfosQuery = useSvcInfos();
  const [voteRequestContractId, setVoteRequestContractId] = useState<
    ContractId<VoteRequest> | undefined
  >(undefined);
  const [isVoteRequestModalOpen, setVoteRequestModalOpen] = useState<boolean>(false);

  const openModalWithVoteRequest = (voteRequestContractId: ContractId<VoteRequest>) => {
    setVoteRequestContractId(voteRequestContractId);
    setVoteRequestModalOpen(true);
  };

  const handleClose = () => setVoteRequestModalOpen(false);

  const svPartyId = svcInfosQuery.data?.svPartyId;
  const alreadyVotedRequestIds = useMemo(() => {
    return svPartyId && votesQuery.data
      ? new Set(votesQuery.data.filter(v => v.voter === svPartyId).map(v => v.requestCid))
      : new Set();
  }, [votesQuery.data, svPartyId]);

  if (listVoteRequestsQuery.isLoading || svcInfosQuery.isLoading || votesQuery.isLoading) {
    return <Loading />;
  }

  if (listVoteRequestsQuery.isError || svcInfosQuery.isError || votesQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const voteRequests = listVoteRequestsQuery.data.sort((a, b) => {
    return parseInt(b.metadata.createdAt) - parseInt(a.metadata.createdAt);
  });

  const voteRequestsNotVoted = voteRequests.filter(v => !alreadyVotedRequestIds.has(v.contractId));
  const voteRequestsVoted = voteRequests.filter(v => alreadyVotedRequestIds.has(v.contractId));

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
        case 'CRARC_SetConfigSchedule': {
          return `${coinRulesAction.tag}`;
        }
      }
    }
    return 'Action tag not defined.';
  }

  return (
    <>
      <Typography mt={6} variant="h4">
        Votes
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
      />
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
  openModalWithVoteRequest: (contractId: ContractId<VoteRequest>) => void;
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
}) => {
  const [open, setOpen] = useState(-1);
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
          <CopyableTypography text={action} />
        </TableCell>
        <TableCell className="vote-row-requester">{requester}</TableCell>
        <TableCell>
          <DateDisplay datetime={expires.toISOString()} />
        </TableCell>
        <TableCell className="vote-row-review">
          <Button
            size="small"
            variant="contained"
            color="primary"
            onClick={() => openModalWithVoteRequest(contractId)}
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
                      <span style={{ fontWeight: 'bold' }}>Description:</span> {description}
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
  getAction: (action: ActionRequiringConfirmation) => string;
  svcRules: Contract<SvcRules>;
  openModalWithVoteRequest: (voteRequestContractId: ContractId<VoteRequest>) => void;
  tableBodyId: string;
}

const ListVoteRequestsTable: React.FC<ListVoteRequestsTableProps> = ({
  voteRequests,
  getAction,
  svcRules,
  openModalWithVoteRequest,
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
                action={getAction(request.payload.action)}
                requester={svcRules.payload.members.get(request.payload.requester)?.name!}
                expires={new Date(request.payload.expiresAt)}
                idx={index}
                description={request.payload.reason.body}
                url={request.payload.reason.url}
                openModalWithVoteRequest={openModalWithVoteRequest}
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
