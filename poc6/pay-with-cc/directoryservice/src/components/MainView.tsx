// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from 'react';
import { Container, Divider, Grid, Header } from 'semantic-ui-react';
import EntryList from './EntryList';
import { useLedger, useParty, useStreamFetchByKeys, useStreamQueries } from '@daml/react';
import { DirectoryEntry, DirectoryRules, DirectoryServiceAppInstall } from '@daml.js/canton-coin/lib/CNS/PayWithCC';
import { Party } from '@daml/types';
import EntryForm from './EntryForm';

const MainView: React.FC = () => {
  const party = useParty();
  const installs = useStreamQueries(DirectoryServiceAppInstall, () => [{ user: party }]);
  const install = (!installs.loading && installs.contracts.length === 1) ? installs.contracts[0].payload : undefined;
  const provider: Party | undefined = install?.provider;
  const entries = useStreamQueries(DirectoryEntry, () => [{ provider: provider }], [provider])
  const rules = useStreamFetchByKeys(DirectoryRules, () => [provider ?? ""], [provider]);
  const rule = (!rules.loading && rules.contracts.length === 1) ? rules.contracts[0]?.payload : undefined;
  const ledger = useLedger();

  const onRequestEntry = async (payload: string): Promise<boolean> => {
    try {
      await ledger.exerciseByKey(DirectoryServiceAppInstall.DirectoryServiceAppInstall_RequestDirectoryEntry, {_1: provider, _2: party}, { entry: { user: party, provider: provider ?? "", payload: payload } });
      window.location.assign(install?.ccWalletUrl ?? "");
      return true;
    } catch (error) {
      alert(`Unknown error:\n${JSON.stringify(error)}`);
      return false;
    }
  };

  if (!provider || !rule) {
    return <div>Loading ...</div>;
  } else {
    return (
      <Container>
        <Grid>
          <Grid.Row>
            <Grid.Column>
              <Grid>
                <Grid.Row columns="equal" verticalAlign="middle">
                  <Grid.Column>
                    <Header as="h1">Directory Service</Header>
                  </Grid.Column>
                </Grid.Row>
              </Grid>
            </Grid.Column>
          </Grid.Row>
          <Divider />
          <Grid.Row>
            <Grid.Column>
              <Header as="h2">Directory Entries</Header>
              <EntryList
                entries={entries}
              />
            </Grid.Column>
          </Grid.Row>
          <Divider />
          <Grid.Row>
            <Grid.Column>
              <Header as="h2">Request Entry</Header>
              <EntryForm onRequestEntry={onRequestEntry} price={rules.contracts[0]?.payload.entryFee ?? ""} />
            </Grid.Column>
          </Grid.Row>
        </Grid>
      </Container>
    );
  }
}

export default MainView;
