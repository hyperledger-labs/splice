import React, { useCallback, useEffect } from 'react';

import { Autocomplete, StandardTextFieldProps, TextField } from '@mui/material';

import { CnsEntry } from '@daml.js/cns/lib/CN/Cns';
import { Party } from '@daml/types';

import { useScanClient } from '../contexts';
import { Contract } from '../utils';

interface Props extends StandardTextFieldProps {
  onPartyChanged: (newParty: Party) => void;
}

type UserInput = { type: 'typed'; value: string } | { type: 'selected'; value: string };

const DirectoryField: React.FC<Props> = ({ onPartyChanged, ...props }) => {
  const scanClient = useScanClient();
  const [options, setOptions] = React.useState<Contract<CnsEntry>[]>([]);
  const [resolvedParty, setResolvedPartyId] = React.useState<string>('');
  const [userInput, setUserInput] = React.useState<UserInput>({ type: 'typed', value: '' });
  const onInputChange = async (event: React.SyntheticEvent, newValue: string, reason: string) => {
    if (reason === 'reset') {
      return;
    }
    setUserInput({ type: 'typed', value: newValue });
  };

  useEffect(() => {
    let effectCancelled = false;
    const fetchCompletions = async () => {
      if (userInput.type === 'typed') {
        // TODO: (#8692) replace this with react query
        const entries = (await scanClient.listEntries(20, userInput.value)).entries;
        const decoded = entries.map(c => Contract.decodeOpenAPI(c, CnsEntry));
        if (!effectCancelled) {
          setOptions(decoded);
        }
      }
    };
    fetchCompletions();
    return () => {
      effectCancelled = true;
    };
  }, [userInput, scanClient]);

  const onItemSelected = async (
    event: React.SyntheticEvent,
    item: string | Contract<CnsEntry> | null
  ) => {
    if (item === null || typeof item === 'string') {
      return;
    }
    // User selected an item from the auto-complete dropdown. Use the party associated with that entry.
    setUserInput({ type: 'selected', value: item.payload.user });
  };

  const resolveUserInput = useCallback(
    async (input: string) => {
      if (input !== undefined || input !== null || input !== '') {
        try {
          // TODO: (#8692) replace this with react query
          const entry = await scanClient.lookupEntryByName(input);
          if (entry === undefined) {
            // Could not lookup cns name - assume input is a party ID
            return input;
          } else {
            // Lookup succeeded - the user typed a valid cns entry - use the resolved party ID
            return entry.payload.user;
          }
        } catch {
          // Input is not a known cns name - assume it is as a party ID
          return input;
        }
      } else {
        return input;
      }
    },
    [scanClient]
  );

  useEffect(() => {
    const setPartyAndNotify = (party: string) => {
      onPartyChanged(party);
      setResolvedPartyId(party);
    };

    let effectCancelled = false;

    const resolveParty = async () => {
      switch (userInput.type) {
        case 'selected':
          setPartyAndNotify(userInput.value);
          break;
        case 'typed':
          const resolved = await resolveUserInput(userInput.value);
          if (!effectCancelled) {
            setPartyAndNotify(resolved);
          }
          break;
      }
    };
    resolveParty();
    return () => {
      effectCancelled = true;
    };
  }, [userInput, resolveUserInput, onPartyChanged]);

  return (
    <Autocomplete
      filterOptions={x => x}
      renderInput={params => (
        <TextField
          {...params}
          fullWidth
          inputProps={{ ...params.inputProps, 'data-resolved-party-id': resolvedParty }}
        />
      )}
      options={options}
      getOptionLabel={(option: string | Contract<CnsEntry>) =>
        typeof option === 'string' ? option : option.payload.name
      }
      onInputChange={onInputChange}
      onChange={onItemSelected}
      freeSolo
      id={props.id}
      className={props.className}
      sx={{ width: 200 }}
    />
  );
};

export default DirectoryField;
