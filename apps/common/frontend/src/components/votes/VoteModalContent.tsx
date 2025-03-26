// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useVotesHooks } from '@lfdecentralizedtrust/splice-common-frontend';
import { CopyableTypography, PartyId, SvVote } from '@lfdecentralizedtrust/splice-common-frontend';
import React, { ReactElement, useCallback } from 'react';

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

import {
  ActionRequiringConfirmation,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId, Party } from '@daml/types';

import { Reason } from '../../models';
import DateWithDurationDisplay from '../DateWithDurationDisplay';
import ActionView from './ActionView';
import { VoteRequestResultTableType } from './VoteResultsFilterTable';

interface VoteModalProps {
  voteRequestContractId: ContractId<VoteRequest>;
  actionReq: ActionRequiringConfirmation;
  requester: Party;
  reason: Reason;
  voteBefore: Date;
  rejectedVotes: SvVote[];
  acceptedVotes: SvVote[];
  voteForm?: (
    voteRequestContractId: ContractId<VoteRequest>,
    currentSvVote: SvVote | undefined
  ) => React.ReactNode;
  curSvVote?: SvVote;
  tableType?: VoteRequestResultTableType;
  effectiveAt?: string;
}

const VoteModalContent: React.FC<VoteModalProps> = ({
  voteRequestContractId,
  actionReq,
  requester,
  reason,
  voteBefore,
  rejectedVotes,
  acceptedVotes,
  voteForm,
  curSvVote,
  tableType,
  effectiveAt,
}) => {
  const votesHooks = useVotesHooks();

  const dsoInfosQuery = votesHooks.useDsoInfos();

  const getMemberName = useCallback(
    (partyId: string) => {
      const member = dsoInfosQuery.data?.dsoRules.payload.svs.get(partyId);
      return member ? member.name : '';
    },
    [dsoInfosQuery.data]
  );

  return (
    <>
      <CardContent sx={{ paddingX: '64px' }}>
        <Stack direction="column" mb={4} spacing={1}>
          <Typography variant="h5">Requested Action</Typography>
          <ActionView action={actionReq} tableType={tableType} effectiveAt={effectiveAt} />
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
                    <CopyableTypography
                      variant="body2"
                      id={'vote-request-modal-content-contract-id'}
                      text={voteRequestContractId}
                    />
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>
                    <Typography variant="h6">Requested by</Typography>
                  </TableCell>
                  <TableCell>
                    <PartyId id="vote-request-modal-requested-by" partyId={requester} />
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>
                    <Typography variant="h6">Proposal Summary</Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="h6" id="vote-request-modal-reason-body">
                      {reason.body}
                    </Typography>
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>
                    <Typography variant="h6">Proposal URL</Typography>
                  </TableCell>
                  <TableCell>
                    <Link href={reason.url} id="vote-request-modal-reason-url">
                      {reason.url}
                    </Link>
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell>
                    <Typography variant="h6">Expires At</Typography>
                  </TableCell>
                  <TableCell>
                    <DateWithDurationDisplay datetime={voteBefore} enableDuration />
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
        {voteForm && voteRequestContractId && (
          <Stack>{voteForm(voteRequestContractId, curSvVote)}</Stack>
        )}
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

export default VoteModalContent;
