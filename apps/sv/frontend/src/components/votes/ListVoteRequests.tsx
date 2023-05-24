import { DateDisplay, Loading, SvClientProvider } from 'common-frontend';
import React, { useState } from 'react';

import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown';
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp';
import {
  Box,
  Collapse,
  IconButton,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules';

import { useSvcInfos } from '../../contexts/SvContext';
import { useListSvcRulesVoteRequests } from '../../hooks/useListVoteRequests';
import { config } from '../../utils';

const ListVoteRequests: React.FC = () => {
  const ListVoteRequestsQuery = useListSvcRulesVoteRequests();
  const svcInfosQuery = useSvcInfos();

  if (ListVoteRequestsQuery.isLoading || svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (ListVoteRequestsQuery.isError || svcInfosQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const voteRequests = ListVoteRequestsQuery.data.sort((a, b) => {
    return parseInt(b.metadata.createdAt) - parseInt(a.metadata.createdAt);
  });

  function getAction(action: ActionRequiringConfirmation) {
    if (action.tag === 'ARC_SvcRules') {
      const svcRulesAction = action.value.svcAction;
      if (svcRulesAction.tag === 'SRARC_RemoveMember') {
        return `${svcRulesAction.tag} : ${svcInfosQuery.data!.svcRules.payload.members.get(
          svcRulesAction.value.member
        )?.name!}`;
      } else {
        throw Error('Action tag not defined.');
      }
    }
  }

  return (
    <>
      <Typography mt={6} variant="h4">
        Votes
      </Typography>
      <Typography mt={6} variant="h5">
        Action Needed
      </Typography>
      <TableContainer>
        <Table style={{ tableLayout: 'auto' }} className="sv-voting-table">
          <TableHead>
            <TableRow>
              <TableCell></TableCell>
              <TableCell>Action</TableCell>
              <TableCell>Requester</TableCell>
              <TableCell>Expires At</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {voteRequests.map((request, index) => {
              return (
                <VoteRequestRow
                  key={request.contractId}
                  action={getAction(request.payload.action)}
                  requester={
                    svcInfosQuery.data!.svcRules.payload.members.get(request.payload.requester)
                      ?.name!
                  }
                  expires={new Date(request.payload.expiresAt)}
                  idx={index}
                  description={request.payload.reason.body}
                  url={request.payload.reason.url}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </>
  );
};

interface VoteRequestsRowProps {
  action: string | undefined;
  requester: string;
  expires: Date;
  idx: number;
  description: string;
  url: string;
}

const VoteRequestRow: React.FC<VoteRequestsRowProps> = ({
  action,
  requester,
  expires,
  idx,
  description,
  url,
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
        <TableCell>{action}</TableCell>
        <TableCell>{requester}</TableCell>
        <TableCell>
          <DateDisplay datetime={expires.toISOString()} />
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
                      <span style={{ fontWeight: 'bold' }}>URL:</span> {url}
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

const ListVoteRequestsWithContexts: React.FC = () => {
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ListVoteRequests />
    </SvClientProvider>
  );
};

export default ListVoteRequestsWithContexts;
