// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from "react";
import { Table } from "semantic-ui-react";
import { QueryResult } from "@daml/react";
import { CreateEvent } from "@daml/ledger";
import { shortenContractId } from "../Utils";
import { DirectoryEntry } from "@daml.js/canton-coin/lib/CNS/PayWithCC";

type Props = {
  entries: QueryResult<DirectoryEntry, undefined, typeof DirectoryEntry.templateId>;
};

type EntryProps = {
  entry: CreateEvent<DirectoryEntry>,
}

const EntryRow: React.FC<EntryProps> = ({ entry }) => {
  return (
    <Table.Row key={entry.contractId}>
      <Table.Cell>
        {shortenContractId(entry.contractId)}
      </Table.Cell>
      <Table.Cell>
        {entry.payload.user}
      </Table.Cell>
      <Table.Cell>
        {entry.payload.payload}
      </Table.Cell>
    </Table.Row>
  );
};

const EntryList: React.FC<Props> = ({
  entries
}) => {
  if (entries.loading) {
    return <div>Loading …</div>
  } else {
    return (
      <Table basic>
        <Table.Header>
          <Table.Row>
            <Table.HeaderCell>ID</Table.HeaderCell>
            <Table.HeaderCell>User</Table.HeaderCell>
            <Table.HeaderCell>Payload</Table.HeaderCell>
          </Table.Row>
        </Table.Header>
        <Table.Body>
          {[...entries.contracts]
            .map(entry => (
              <EntryRow key={entry.contractId} entry={entry} />
            ))}
        </Table.Body>
      </Table>
    );
  }
};

export default EntryList;
