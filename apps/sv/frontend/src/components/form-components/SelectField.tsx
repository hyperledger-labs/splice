// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  Box,
  FormControl,
  FormHelperText,
  MenuItem,
  Select,
  SelectChangeEvent,
  Typography,
} from '@mui/material';
import type { FormEvent } from 'react';
import { useFieldContext } from '../../hooks/formContext';

export type Option = { key: string; value: string };
export interface SelectFieldProps {
  title: string;
  options: Option[];
  id: string;
  onChange?: () => void;
  disabled?: boolean;
}

export const SelectField: React.FC<SelectFieldProps> = props => {
  const { title, options, id, disabled = false } = props;
  const externalOnChange = props.onChange ?? (() => {});
  const field = useFieldContext<string>();
  const handleSelectValueChange = (value: string) => {
    field.handleChange(value);
    externalOnChange();
  };

  return (
    <Box data-testid={`${id}-select-component`}>
      <Typography variant="h6" gutterBottom>
        {title}
      </Typography>

      <FormControl variant="outlined" error={!field.state.meta.isValid} fullWidth>
        <Select
          value={field.state.value}
          onChange={(e: SelectChangeEvent) => {
            handleSelectValueChange(e.target.value as string);
          }}
          onBlur={field.handleBlur}
          error={!field.state.meta.isValid}
          disabled={disabled}
          id={`${id}-dropdown`}
          data-testid={id}
          inputProps={{
            'data-testid': `${id}-dropdown`,
            onChange: (e: FormEvent<HTMLInputElement | HTMLTextAreaElement>) => {
              handleSelectValueChange((e.target as HTMLInputElement).value);
            },
          }}
        >
          {options.map((member, index) => (
            <MenuItem
              key={'option-' + index}
              value={member.value}
              data-testid={`option-${member.key}`}
            >
              {member.key}
            </MenuItem>
          ))}
        </Select>
        <FormHelperText data-testid={`${id}-error`}>{field.state.meta.errors?.[0]}</FormHelperText>
      </FormControl>
    </Box>
  );
};
