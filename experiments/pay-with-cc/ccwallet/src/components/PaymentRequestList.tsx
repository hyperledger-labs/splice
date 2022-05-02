// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from "react";
import { Button, Table } from "semantic-ui-react";
import { QueryResult } from "@daml/react";
import { CreateEvent } from "@daml/ledger";
import { shortenContractId } from "../Util";
import { PaymentRequest } from "@daml.js/pay-with-cc/lib/PayWithCC";

type Props = {
  requests: QueryResult<PaymentRequest, undefined, typeof PaymentRequest.templateId>;
  onApprove: (request: CreateEvent<PaymentRequest>) => Promise<boolean>,
};

type PaymentRequestProps = {
  request: CreateEvent<PaymentRequest>,
  onApprove: (request: CreateEvent<PaymentRequest>) => Promise<boolean>,
}

const PaymentRequestRow: React.FC<PaymentRequestProps> = ({ request, onApprove }) => {
  return (
    <Table.Row key={request.contractId}>
      <Table.Cell>
        {shortenContractId(request.contractId)}
      </Table.Cell>
      <Table.Cell>
        {request.payload.quantity}
      </Table.Cell>
      <Table.Cell>
        {request.payload.provider}
      </Table.Cell>
      <Table.Cell>
        {request.payload.expiresAt}
      </Table.Cell>
      <Table.Cell>
        {parseInt(request.payload.collectionDuration.microseconds) / 1000 / 60} min
      </Table.Cell>
      <Table.Cell>
        <Button positive onClick={() => onApprove(request)}>Approve</Button>
      </Table.Cell>
    </Table.Row>
  );
};

const PaymentRequestList: React.FC<Props> = ({
  requests,
  onApprove
}) => {
  if (requests.loading) {
    return <div>Loading …</div>
  } else {
    return (
      <Table basic>
        <Table.Header>
          <Table.Row>
            <Table.HeaderCell>ID</Table.HeaderCell>
            <Table.HeaderCell>Quantity</Table.HeaderCell>
            <Table.HeaderCell>Requester</Table.HeaderCell>
            <Table.HeaderCell>Expires at</Table.HeaderCell>
            <Table.HeaderCell>Collection duration</Table.HeaderCell>
          </Table.Row>
        </Table.Header>
        <Table.Body>
          {[...requests.contracts]
            .map(request => (
              <PaymentRequestRow key={request.contractId} request={request} onApprove={onApprove} />
            ))}
        </Table.Body>
      </Table>
    );
  }
};

export default PaymentRequestList;
