// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseQueryResult } from '@tanstack/react-query';
import React, { useEffect } from 'react';
import { AnsEntry } from '@lfdecentralizedtrust/scan-openapi';

import { Autocomplete, StandardTextFieldProps, TextField } from '@mui/material';

import { Party } from '@daml/types';

import useListAnsEntries from '../api/scan/useListAnsEntries';
import useLookupAnsEntryByName from '../api/scan/useLookupAnsEntryByName';

export interface AnsFieldProps extends StandardTextFieldProps {
  onPartyChanged: (newParty: Party) => void;
}

export type UserInput = { type: 'typed'; value: string } | { type: 'selected'; value: string };

const AnsField: React.FC<AnsFieldProps> = props => {
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

export const BaseAnsField: React.FC<BaseAnsFieldProps> = propas => {
  const { userInput, updateUserInput, onPartyChanged, ansEntries, ansEntry, ...props } = propas;
  const [resolvedPartyId, setResolvedPartyId] = React.useState<string>('');
  const nameServiceAcronym =
    window.splice_config.spliceInstanceNames?.nameServiceNameAcronym.toLowerCase();
  const dsoEntryName = `dso.${nameServiceAcronym}`;
  const filteredAnsEntriesData = ansEntries.data?.filter(entry => entry.name !== dsoEntryName);

  useEffect(() => {
    const setPartyAndNotify = (party: string) => {
      onPartyChanged(party);
      setResolvedPartyId(party);
    };
    const ansEntryParty = ansEntry.data?.user || userInput.value;
    if (resolvedPartyId !== ansEntryParty) {
      // prevent infinite loop
      setPartyAndNotify(ansEntryParty);
    }
  }, [userInput, ansEntry, resolvedPartyId, onPartyChanged]);

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

export default AnsField;
