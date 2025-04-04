// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { JSONValue, JsonEditor } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useState } from 'react';

import { NativeSelect, Stack } from '@mui/material';

import { Tuple2 } from '@daml.js/5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4/lib/DA/Types';
import { AmuletConfig, USD } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';

dayjs.extend(utc);

const PrettyJsonPrint: React.FC<{
  data: Record<string, JSONValue>;
}> = ({ data }) => {
  return (
    <pre style={{ whiteSpace: 'pre-wrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
      {typeof data !== 'string' ? JSON.stringify(data, null, 2) : data}
    </pre>
  );
};

type DropdownState =
  | { label: 'No Selection' }
  | { label: string; value: Record<string, JSONValue> };
function stateHasValue(
  state: DropdownState
): state is { label: string; value: Record<string, JSONValue> } {
  return state.label !== 'No Selection';
}
// TODO (#10209): this component is handling both the PrettyPrint and the JsonEditor. Split into two components.
export const DropdownSchedules: React.FC<{
  futureValues: Tuple2<string, AmuletConfig<USD>>[];
  initialValue?: AmuletConfig<USD>;
  onChange?: (item: string) => void;
  onChangeEditor?: (date: string, config: Record<string, JSONValue>) => void;
}> = ({ initialValue, futureValues, onChange, onChangeEditor }) => {
  interface DropdownOption {
    value: Record<string, JSONValue> | null;
    label: string;
  }

  // TODO (#10209): remove this intermediate state by lifting it to VoteRequest.tsx
  const [selectedOption, setSelectedOption] = useState<DropdownState>({ label: 'No Selection' });

  const dropdownOptions: DropdownOption[] = [
    { value: null, label: 'No Selection' },
    ...futureValues.map(value => ({
      value: AmuletConfig(USD).encode(value._2) as Record<string, JSONValue>,
      label: dayjs(value._1).toString().replace('GMT', 'UTC'),
    })),
  ];

  if (initialValue) {
    dropdownOptions.unshift({
      value: AmuletConfig(USD).encode(initialValue) as Record<string, JSONValue>,
      label: 'Current Configuration',
    });
  }

  const handleOptionChange = (optionDate: string) => {
    const convertedDate = dayjs
      .utc(optionDate.replace('UTC', 'GMT'))
      .format('YYYY-MM-DDTHH:mm:00[Z]');
    if (optionDate === 'No Selection') {
      setSelectedOption({ label: 'No Selection' });
    } else {
      const valueForLabel = dropdownOptions.filter(e => e.label === optionDate)[0].value!;
      setSelectedOption({ label: optionDate, value: valueForLabel });
    }
    if (onChange) {
      onChange(convertedDate);
    }
  };

  async function updateFutureAmuletConfigScheduleAction(config: Record<string, JSONValue>) {
    if (onChangeEditor && selectedOption.label !== 'No Selection') {
      setSelectedOption({ label: selectedOption.label, value: config });
      onChangeEditor(selectedOption.label, config);
    }
  }

  return (
    <Stack key={selectedOption.label}>
      <NativeSelect
        inputProps={{ id: 'dropdown-display-schedules-datetime' }}
        value={selectedOption}
        onChange={e => handleOptionChange(e.target.value)}
      >
        {dropdownOptions &&
          dropdownOptions.map((option, index) => (
            <option key={'member-option-' + index} value={option.label}>
              {option.label}
            </option>
          ))}
      </NativeSelect>
      {onChange && stateHasValue(selectedOption) && <PrettyJsonPrint data={selectedOption.value} />}
      {onChangeEditor && stateHasValue(selectedOption) && (
        <JsonEditor data={selectedOption.value} onChange={updateFutureAmuletConfigScheduleAction} />
      )}
    </Stack>
  );
};
