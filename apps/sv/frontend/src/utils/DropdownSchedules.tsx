import { JSONValue, JsonEditor } from 'common-frontend-utils';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useState } from 'react';

import { NativeSelect, Stack } from '@mui/material';

import { Tuple2 } from '@daml.js/87530dd1038863bad7bdf02c59ae851bc00f469edb2d7dbc8be3172daafa638c/lib/DA/Types';
import { CoinConfig, USD } from '@daml.js/canton-coin/lib/CC/CoinConfig';

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

export const DropdownSchedules: React.FC<{
  futureValues: Tuple2<string, CoinConfig<USD>>[];
  initialValue?: CoinConfig<USD>;
  onChange?: (item: string) => void;
  onChangeEditor?: (date: string, config: Record<string, JSONValue>) => void;
}> = ({ initialValue, futureValues, onChange, onChangeEditor }) => {
  interface DropdownOption {
    value: Record<string, JSONValue> | null;
    label: string;
  }

  const dropdownOptions: DropdownOption[] = [
    { value: null, label: 'No Selection' },
    ...futureValues.map(value => ({
      value: CoinConfig(USD).encode(value._2) as Record<string, JSONValue>,
      label: dayjs(value._1).toString().replace('GMT', 'UTC'),
    })),
  ];

  if (initialValue) {
    dropdownOptions.unshift({
      value: CoinConfig(USD).encode(initialValue) as Record<string, JSONValue>,
      label: 'Current Configuration',
    });
  }

  const [selectedOption, setSelectedOption] = useState<string>('No Selection');

  const handleOptionChange = (optionDate: string) => {
    setSelectedOption(optionDate);
    const convertedDate = dayjs
      .utc(optionDate.replace('UTC', 'GMT'))
      .format('YYYY-MM-DDTHH:mm:00[Z]');
    if (onChange) {
      onChange(convertedDate);
    }
  };

  async function updateFutureCoinConfigScheduleAction(config: Record<string, JSONValue>) {
    if (onChangeEditor && selectedOption !== 'No Selection') {
      onChangeEditor(selectedOption, config);
    }
  }

  return (
    <Stack key={selectedOption}>
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
      {onChange &&
        dropdownOptions.filter(e => e.label === selectedOption).length > 0 &&
        dropdownOptions.filter(e => e.label === selectedOption)[0].value !== null && (
          <PrettyJsonPrint
            data={dropdownOptions.filter(e => e.label === selectedOption)[0].value!}
          />
        )}
      {onChangeEditor &&
        dropdownOptions.filter(e => e.label === selectedOption).length > 0 &&
        dropdownOptions.filter(e => e.label === selectedOption)[0].value !== null && (
          <JsonEditor
            data={dropdownOptions.filter(e => e.label === selectedOption)[0].value!}
            onChange={updateFutureCoinConfigScheduleAction}
          />
        )}
    </Stack>
  );
};
