import { UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';
import React, { useEffect } from 'react';

import { Autocomplete, StandardTextFieldProps, TextField } from '@mui/material';

import { CnsEntry } from '@daml.js/cns/lib/CN/Cns';
import { Party } from '@daml/types';

import useListCnsEntries from '../api/scan/useListCnsEntries';
import useLookupCnsEntryByName from '../api/scan/useLookupCnsEntryByName';

export interface CnsFieldProps extends StandardTextFieldProps {
  onPartyChanged: (newParty: Party) => void;
}

export type UserInput = { type: 'typed'; value: string } | { type: 'selected'; value: string };

const CnsField: React.FC<CnsFieldProps> = props => {
  const [userInput, setUserInput] = React.useState<UserInput>({ type: 'typed', value: '' });
  const cnsEntriesQuery = useListCnsEntries(20, userInput.value);
  const cnsEntryQuery = useLookupCnsEntryByName(userInput.value, userInput.type === 'typed');

  return (
    <BaseCnsField
      {...props}
      userInput={userInput}
      updateUserInput={setUserInput}
      cnsEntries={cnsEntriesQuery}
      cnsEntry={cnsEntryQuery}
    />
  );
};

interface BaseCnsFieldProps extends CnsFieldProps {
  userInput: UserInput;
  updateUserInput: (userInput: UserInput) => void;
  cnsEntries: UseQueryResult<Contract<CnsEntry>[]>;
  cnsEntry: UseQueryResult<Contract<CnsEntry>>;
}

export const BaseCnsField: React.FC<BaseCnsFieldProps> = ({
  userInput,
  updateUserInput,
  onPartyChanged,
  cnsEntries,
  cnsEntry,
  ...props
}) => {
  const [resolvedPartyId, setResolvedPartyId] = React.useState<string>('');

  useEffect(() => {
    const setPartyAndNotify = (party: string) => {
      onPartyChanged(party);
      setResolvedPartyId(party);
    };
    const cnsEntryParty = cnsEntry.data?.payload.user || userInput.value;
    setPartyAndNotify(cnsEntryParty);
  }, [userInput, cnsEntry, onPartyChanged]);

  const onInputChange = async (_: React.SyntheticEvent, newValue: string, reason: string) => {
    if (reason === 'reset') {
      return;
    }

    if (reason === 'clear') {
      updateUserInput({ type: 'typed', value: '' });
    }

    updateUserInput({ type: 'typed', value: newValue });
  };

  const onItemSelected = async (
    _: React.SyntheticEvent,
    item: Contract<CnsEntry> | string | null
  ) => {
    if (item === null || typeof item === 'string') {
      return;
    }
    // User selected an item from the auto-complete dropdown. Use the party associated with that entry.
    updateUserInput({ type: 'selected', value: item.payload.user });
  };

  return (
    <Autocomplete
      freeSolo
      id={props.id}
      sx={{ width: 200 }}
      className={props.className}
      filterOptions={x => x}
      renderInput={params => (
        <TextField
          {...params}
          fullWidth
          inputProps={{ ...params.inputProps, 'data-resolved-party-id': resolvedPartyId }}
        />
      )}
      options={cnsEntries.data || []}
      getOptionLabel={(option: Contract<CnsEntry> | string) =>
        typeof option === 'string' ? option : option.payload.name
      }
      onInputChange={onInputChange}
      onChange={onItemSelected}
    />
  );
};

export default CnsField;
