import React, { useEffect } from 'react';

import { Autocomplete, StandardTextFieldProps, TextField } from '@mui/material';

import { CnsEntry } from '@daml.js/cns/lib/CN/Cns';
import { Party } from '@daml/types';

import useListCnsEntries from '../api/scan/useListCnsEntries';
import useLookupCnsEntryByName from '../api/scan/useLookupCnsEntryByName';
import { Contract } from '../utils';

interface Props extends StandardTextFieldProps {
  onPartyChanged: (newParty: Party) => void;
}

type UserInput = { type: 'typed'; value: string } | { type: 'selected'; value: string };

const CnsField: React.FC<Props> = ({ onPartyChanged, ...props }) => {
  const [userInput, setUserInput] = React.useState<UserInput>({ type: 'typed', value: '' });
  const [resolvedPartyId, setResolvedPartyId] = React.useState<string>('');
  const cnsEntriesQuery = useListCnsEntries(20, userInput.value);
  const cnsEntryQuery = useLookupCnsEntryByName(userInput.value, userInput.type === 'typed');

  useEffect(() => {
    const setPartyAndNotify = (party: string) => {
      onPartyChanged(party);
      setResolvedPartyId(party);
    };
    const cnsEntryParty = cnsEntryQuery.data?.payload.user || userInput.value;
    setPartyAndNotify(cnsEntryParty);
  }, [userInput, cnsEntryQuery, onPartyChanged]);

  const onInputChange = async (_: React.SyntheticEvent, newValue: string, reason: string) => {
    if (reason === 'reset') {
      return;
    }

    if (reason === 'clear') {
      setUserInput({ type: 'typed', value: '' });
    }

    setUserInput({ type: 'typed', value: newValue });
  };

  const onItemSelected = async (
    _: React.SyntheticEvent,
    item: Contract<CnsEntry> | string | null
  ) => {
    if (item === null || typeof item === 'string') {
      return;
    }
    // User selected an item from the auto-complete dropdown. Use the party associated with that entry.
    setUserInput({ type: 'selected', value: item.payload.user });
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
      options={cnsEntriesQuery.data || []}
      getOptionLabel={(option: Contract<CnsEntry> | string) =>
        typeof option === 'string' ? option : option.payload.name
      }
      onInputChange={onInputChange}
      onChange={onItemSelected}
    />
  );
};

export default CnsField;
