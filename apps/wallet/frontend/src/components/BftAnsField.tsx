// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { AnsFieldProps, UserInput } from '@lfdecentralizedtrust/splice-common-frontend';
import React, { useEffect } from 'react';

import useListAnsEntries from '../hooks/scan-proxy/useListAnsEntries';
import useLookupAnsEntryByName from '../hooks/scan-proxy/useLookupAnsEntryByName';
import { AnsEntry } from 'scan-openapi';
import { Autocomplete, TextField } from '@mui/material';
import { UseQueryResult } from '@tanstack/react-query';

const BftAnsField: React.FC<AnsFieldProps> = props => {
  console.error(`BFT Render: ${JSON.stringify(props)}`);
  const [userInput, setUserInput] = React.useState<UserInput>({ type: 'typed', value: '' });
  const ansEntriesQuery = useListAnsEntries(20, userInput.value);
  const ansEntryQuery = useLookupAnsEntryByName(userInput.value, userInput.type === 'typed');

  return (
    <BaseAnsField
      {...props}
      userInput={userInput}
      updateUserInput={setUserInput}
      ansEntries={ansEntriesQuery}
      ansEntry={ansEntryQuery}
    />
  );
};

interface BaseAnsFieldProps extends AnsFieldProps {
  userInput: UserInput;
  updateUserInput: (userInput: UserInput) => void;
  ansEntries: UseQueryResult<AnsEntry[]>;
  ansEntry: UseQueryResult<AnsEntry>;
}

const BaseAnsField: React.FC<BaseAnsFieldProps> = propas => {
  const { userInput, updateUserInput, onPartyChanged, ansEntries, ansEntry, ...props } = propas;
  console.error(`Base ANS render: ${JSON.stringify(propas)}`);
  const [resolvedPartyId, setResolvedPartyId] = React.useState<string>('');
  const nameServiceAcronym =
    window.splice_config.spliceInstanceNames?.nameServiceNameAcronym.toLowerCase();
  const dsoEntryName = `dso.${nameServiceAcronym}`;
  const filteredAnsEntriesData = ansEntries.data?.filter(entry => entry.name !== dsoEntryName);
  const ansEntryUser = ansEntry.data?.user;

  useEffect(() => {
    console.error(
      `Effecting: ${JSON.stringify(userInput)} :: ${JSON.stringify(ansEntryUser)} :: ${onPartyChanged}`
    );
    const setPartyAndNotify = (party: string) => {
      onPartyChanged(party);
      setResolvedPartyId(party);
    };
    const ansEntryParty = ansEntryUser || userInput.value;
    if (resolvedPartyId !== ansEntryParty) {
      setPartyAndNotify(ansEntryParty);
    }
  }, [userInput, ansEntryUser, resolvedPartyId, onPartyChanged]);

  const onInputChange = async (_: React.SyntheticEvent, newValue: string, reason: string) => {
    if (reason === 'reset') {
      return;
    }

    if (reason === 'clear') {
      updateUserInput({ type: 'typed', value: '' });
    }

    updateUserInput({ type: 'typed', value: newValue });
  };

  const onItemSelected = async (_: React.SyntheticEvent, item: AnsEntry | string | null) => {
    if (item === null || typeof item === 'string') {
      return;
    }
    // User selected an item from the auto-complete dropdown. Use the party associated with that entry.
    updateUserInput({ type: 'selected', value: item.user });
  };

  return (
    <Autocomplete
      freeSolo
      disablePortal
      id={props.id}
      sx={{ width: 1000 }}
      className={props.className}
      filterOptions={x => x}
      renderInput={params => (
        <TextField
          {...params}
          fullWidth
          inputProps={{ ...params.inputProps, 'data-resolved-party-id': resolvedPartyId }}
        />
      )}
      options={filteredAnsEntriesData || []}
      getOptionLabel={(option: AnsEntry | string) =>
        typeof option === 'string' ? option : option.name
      }
      onInputChange={onInputChange}
      onChange={onItemSelected}
    />
  );
};

export default BftAnsField;
