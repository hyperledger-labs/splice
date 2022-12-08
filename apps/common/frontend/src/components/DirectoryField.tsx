import {
  ListEntriesRequest,
  LookupEntryByNameRequest,
} from 'common-protobuf/com/daml/network/directory/v0/directory_service_pb';
import React from 'react';

import { Autocomplete, StandardTextFieldProps, TextField } from '@mui/material';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';
import { Party } from '@daml/types';

import { useDirectoryClient } from '../contexts/DirectoryServiceContext';
import { Contract } from '../utils';

interface Props extends StandardTextFieldProps {
  onPartyChanged: (newParty: Party) => void;
}

const DirectoryField: React.FC<Props> = (props: Props) => {
  const directoryClient = useDirectoryClient();
  const [options, setOptions] = React.useState<Contract<DirectoryEntry>[]>([]);
  const onInputChange = async (event: React.SyntheticEvent, newValue: string, reason: string) => {
    if (reason === 'reset') {
      return;
    }
    const request = new ListEntriesRequest();
    request.setNamePrefix(newValue);
    request.setPageSize(20);
    const entries = (await directoryClient.listEntries(request, undefined)).getEntriesList();
    const decoded = entries.map(c => Contract.decode(c, DirectoryEntry));
    setOptions(decoded);
    await setPartyFromInput(newValue);
  };

  const onItemSelected = async (
    event: React.SyntheticEvent,
    item: string | Contract<DirectoryEntry> | null
  ) => {
    if (item === null || typeof item === 'string') {
      return;
    }
    // User selected an item from the auto-complete dropdown. Use the user associated with that entry.
    setPartyAndNotify(item.payload.user);
  };

  const setPartyFromInput = async (input: string) => {
    const req = new LookupEntryByNameRequest();
    req.setName(input);
    try {
      const entry = (await directoryClient.lookupEntryByName(req)).getEntry();
      if (entry === undefined) {
        // Could not lookup cns name - assume input is a party ID
        setPartyAndNotify(input);
      } else {
        // Lookup succeeded - the user typed a valid cns entry - use the resolved party ID
        setPartyAndNotify(Contract.decode(entry, DirectoryEntry).payload.user);
      }
    } catch {
      // Input is not a known cns name - assume it is as a party ID
      setPartyAndNotify(input);
    }
  };

  const setPartyAndNotify = (party: string) => {
    props.onPartyChanged(party);
  };

  return (
    <Autocomplete
      filterOptions={x => x}
      renderInput={params => <TextField {...params} fullWidth label={props.label} />}
      options={options}
      getOptionLabel={(option: string | Contract<DirectoryEntry>) =>
        typeof option === 'string' ? option : option.payload.name
      }
      onInputChange={onInputChange}
      onChange={onItemSelected}
      freeSolo
      sx={{ width: 200 }}
      id={props.id}
      className={props.className}
    />
  );
};

export default DirectoryField;
