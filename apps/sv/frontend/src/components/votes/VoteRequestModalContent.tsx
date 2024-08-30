// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  CopyableTypography,
  DateDisplay,
  Loading,
  PartyId,
  SvClientProvider,
} from 'common-frontend';
import React, { ReactElement, useCallback, useEffect } from 'react';

import CheckIcon from '@mui/icons-material/Check';
import ClearIcon from '@mui/icons-material/Clear';
import {
  CardContent,
  Link,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId, Party } from '@daml/types';

import { useDsoInfos } from '../../contexts/SvContext';
import { useListVotes } from '../../hooks/useListVotes';
import { useVoteRequest } from '../../hooks/useVoteRequest';
import { SvVote } from '../../models/models';
import { useSvConfig } from '../../utils';
import VoteForm from './VoteForm';
import ActionView from './actions/views/ActionView';

interface VoteRequestModalProps {
  voteRequestContractId?: ContractId<VoteRequest>;
  handleClose: () => void;
}

const VoteRequestModalContent: React.FC<VoteRequestModalProps> = ({
  voteRequestContractId,
  handleClose,
}) => {
  const voteRequestQuery = useVoteRequest(voteRequestContractId);

  const votesQuery = useListVotes(voteRequestContractId ? [voteRequestContractId] : []);

  // allVotes being empty means that the vote request has been executed, as the initiator of the request must vote on his proposition. Therefore, we can close the modal.
  useEffect(() => {
    if (votesQuery.data?.length === 0) {
      handleClose();
    }
  }, [votesQuery, handleClose]);

  const dsoInfosQuery = useDsoInfos();
  const svPartyId = dsoInfosQuery.data?.svPartyId;

  const getMemberName = useCallback(
    (partyId: string) => {
      const member = dsoInfosQuery.data?.dsoRules.payload.svs.get(partyId);
      return member ? member.name : '';
    },
    [dsoInfosQuery.data]
  );

  if (voteRequestQuery.isLoading) {
    return <Loading />;
  }

  if (voteRequestQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!voteRequestQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  if (votesQuery.isLoading) {
    return <Loading />;
  }

  if (votesQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!votesQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const curSvVote: SvVote | undefined = votesQuery.data.find(v => v.voter === svPartyId);

  const allVotes = votesQuery.data.sort((a, b) => {
    return b.expiresAt.valueOf() - a.expiresAt.valueOf();
  });

  const acceptedVotes = allVotes.filter(v => v.accept);
  const rejectedVotes = allVotes.filter(v => !v.accept);

  return (
    <>
      <CardContent sx={{ paddingX: '64px' }}>
        <Stack direction="column" mb={4} spacing={1}>
          <Typography variant="h5">Requested Action</Typography>
          <ActionView action={voteRequestQuery.data.payload.action} />
        </Stack>
        <Stack direction="column" mb={4} spacing={1}>
          <Typography variant="h5">Request Information</Typography>
          <TableContainer>
            <Table style={{ tableLayout: 'auto' }} className="sv-voting-table">
              <TableBody>
                <TableRow>
                  <TableCell>
                    <Typography variant="h6">Contract Id</Typography>
                  </TableCell>
                  <TableCell>
                    {voteRequestContractId && (
                      <CopyableTypography
                        variant="body2"
                        id={'vote-request-modal-content-contract-id'}
                        text={voteRequestContractId}
                      />
                    )}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>
                    <Typography variant="h6">Requested by</Typography>
                  </TableCell>
                  <TableCell>
                    <PartyId
                      id="vote-request-modal-requested-by"
                      partyId={voteRequestQuery.data.payload.requester}
                    />
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>
                    <Typography variant="h6">Proposal Summary</Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="h6" id="vote-request-modal-reason-body">
                      {voteRequestQuery.data.payload.reason.body}
                    </Typography>
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>
                    <Typography variant="h6">Proposal URL</Typography>
                  </TableCell>
                  <TableCell>
                    <Link
                      href={voteRequestQuery.data.payload.reason.url}
                      id="vote-request-modal-reason-url"
                    >
                      {voteRequestQuery.data.payload.reason.url}
                    </Link>
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>
                    <Typography variant="h6">Expires At</Typography>
                  </TableCell>
                  <TableCell>
                    <DateDisplay datetime={voteRequestQuery.data.payload.voteBefore} />
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>
                    <Typography variant="h6">Current Vote Status</Typography>
                  </TableCell>
                  <TableCell color="default">
                    <Stack spacing={4} direction="row">
                      <Typography id="vote-request-modal-rejected-count" variant="h6">
                        <ClearIcon color="error" fontSize="inherit" /> {rejectedVotes.length}
                      </Typography>
                      <Typography id="vote-request-modal-accepted-count" variant="h6">
                        <CheckIcon color="success" fontSize="inherit" /> {acceptedVotes.length}
                      </Typography>
                    </Stack>
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </TableContainer>
        </Stack>
        <Stack>
          {voteRequestContractId && (
            <VoteForm vote={curSvVote} voteRequestCid={voteRequestContractId} />
          )}
        </Stack>
        <Stack direction="column" mb={4} spacing={1}>
          <Typography variant="h5">Votes</Typography>
          <TableContainer>
            <Table style={{ tableLayout: 'fixed' }} className="sv-accepted-vote-table">
              <TableHead>
                <TableRow>
                  <TableCell>Super Validator</TableCell>
                  <TableCell>Super Validator Party ID</TableCell>
                  <TableCell>Reason Summary</TableCell>
                  <TableCell>Reason URL</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                <VoteRows
                  icon={<CheckIcon color="success" fontSize="inherit" />}
                  votesTitle="Accepted"
                  votes={acceptedVotes}
                  getMemberName={getMemberName}
                />
                <VoteRows
                  icon={<ClearIcon color="error" fontSize="inherit" />}
                  votesTitle="Rejected"
                  votes={rejectedVotes}
                  getMemberName={getMemberName}
                />
              </TableBody>
            </Table>
          </TableContainer>
        </Stack>
      </CardContent>
    </>
  );
};

interface VoteRowProps {
  svName: string;
  sv: Party;
  reasonBody: string;
  reasonUrl: string;
}

const VoteRow: React.FC<VoteRowProps> = ({ svName, sv, reasonBody, reasonUrl }) => (
  <TableRow className="vote-table-row">
    <TableCell className="sv-name">{svName}</TableCell>
    <TableCell>
      <PartyId partyId={sv} className="sv-party" />
    </TableCell>
    <TableCell className="vote-reason-body">{reasonBody}</TableCell>
    <TableCell className="url">
      <Link href={reasonUrl}>{reasonUrl}</Link>
    </TableCell>
  </TableRow>
);

const VoteRows: React.FC<{
  icon: ReactElement;
  votes: SvVote[];
  votesTitle: string;
  getMemberName: (svParty: Party) => string;
}> = ({ icon, votes, votesTitle, getMemberName }) => (
  <>
    <TableRow className="vote-table-row">
      {votes.length > 0 && (
        <TableCell className="sv-name">
          <Typography variant="h6">
            {icon} {votesTitle}
          </Typography>
        </TableCell>
      )}
    </TableRow>
    {votes.map((vote: SvVote) => {
      return (
        <VoteRow
          key={vote.voter}
          sv={vote.voter}
          svName={getMemberName(vote.voter)}
          reasonBody={vote.reason?.body || ''}
          reasonUrl={vote.reason?.url || ''}
        />
      );
    })}
  </>
);

const VoteRequestModalContentWithContexts: React.FC<VoteRequestModalProps> = props => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <VoteRequestModalContent {...props} />
    </SvClientProvider>
  );
};

export default VoteRequestModalContentWithContexts;
