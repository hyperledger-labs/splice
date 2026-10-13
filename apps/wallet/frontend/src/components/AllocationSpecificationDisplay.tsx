// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import {
  Box,
  Container,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
} from '@mui/material';
import Typography from '@mui/material/Typography';
import BftAnsEntry from './BftAnsEntry';
import { AllocationSpecification } from '@daml.js/splice-api-token-allocation-v2/lib/Splice/Api/Token/AllocationV2/module';
import BigNumber from 'bignumber.js';
import { DateWithDurationDisplay } from '@canton-network/splice-common-frontend';
import { TextMapDisplay } from './TextMap';

const AllocationSpecificationDisplay: React.FC<{
  parentId: string;
  spec: AllocationSpecification;
  getActionButton: () => React.ReactNode | null;
}> = ({ spec, parentId, getActionButton }) => {
  const {
    authorizer,
    committed,
    meta,
    nextIterationFunding,
    settlementDeadline,
    transferLegSides,
  } = spec;
  const sortedLegs = transferLegSides.toSorted((a, b) =>
    a.transferLegId.localeCompare(b.transferLegId)
  );
  const hasAllocationMeta = Object.keys(meta.values).length > 0;
  const hasNextIterationFunding =
    !!nextIterationFunding && Object.keys(nextIterationFunding).length > 0;
  return (
    <Container>
      <Typography variant="body2">Allocation with Authorizer</Typography>
      <BftAnsEntry
        partyId={authorizer.owner ?? '<unexpected: missing authorizer party>'}
        className="allocation-authorizer"
      />
      {settlementDeadline ? (
        <Typography variant="body2">
          (Settlement deadline:{' '}
          <DateWithDurationDisplay datetime={settlementDeadline} enableDuration />)
        </Typography>
      ) : null}
      <Typography variant="body2" className="allocation-committed">
        Committed: {committed ? 'yes' : 'no'}
      </Typography>
      {hasNextIterationFunding ? (
        <>
          <Typography variant="body2">Next iteration funding</Typography>
          <Box className="allocation-next-iteration-funding">
            <TextMapDisplay textMap={nextIterationFunding} />
          </Box>
        </>
      ) : null}
      {hasAllocationMeta ? (
        <>
          <Typography variant="body2">Allocation Meta</Typography>
          <TextMapDisplay textMap={meta.values} />
        </>
      ) : null}
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Id</TableCell>
            <TableCell>Sender</TableCell>
            <TableCell>Receiver</TableCell>
            <TableCell align="right">Amount</TableCell>
            <TableCell>Meta</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {sortedLegs.map(transferLeg => {
            const { meta, otherside, side, instrumentId, amount, transferLegId } = transferLeg;
            const sender = side === 'SenderSide' ? authorizer : otherside;
            const receiver = side === 'ReceiverSide' ? authorizer : otherside;
            const id = `transfer-leg-${parentId}-${transferLegId}`;
            return (
              <TableRow key={transferLegId} id={id} className="allocation-row">
                <TableCell>
                  <Typography variant="body2" className="allocation-legid">
                    {transferLegId}
                  </Typography>
                </TableCell>
                <TableCell>
                  <Stack>
                    <BftAnsEntry
                      partyId={sender.owner ?? '<unexpected: missing owner party>'}
                      className="allocation-sender"
                    />
                    {sender.provider ? (
                      <BftAnsEntry partyId={sender.provider} className="allocation-provider" />
                    ) : null}
                    {sender.id ? (
                      <Typography variant="caption" color="text.secondary">
                        Account: {sender.id}
                      </Typography>
                    ) : null}
                  </Stack>
                </TableCell>
                <TableCell>
                  <Stack>
                    <BftAnsEntry
                      partyId={receiver.owner ?? '<unexpected: missing owner party>'}
                      className="allocation-receiver"
                    />
                    {receiver.provider ? (
                      <Typography variant="caption" color="text.secondary">
                        Provider: {receiver.provider}
                      </Typography>
                    ) : null}
                    {receiver.id ? (
                      <Typography variant="caption" color="text.secondary">
                        Account: {receiver.id}
                      </Typography>
                    ) : null}
                  </Stack>
                </TableCell>
                <TableCell>
                  <Typography variant="body2" className="allocation-amount-instrument">
                    {BigNumber(amount).toFormat()} {instrumentId}
                  </Typography>
                </TableCell>
                <TableCell>
                  <TextMapDisplay textMap={meta.values} />
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
      {getActionButton()}
    </Container>
  );
};

export default AllocationSpecificationDisplay;
