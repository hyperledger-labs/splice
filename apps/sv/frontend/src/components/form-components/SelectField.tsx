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
import { useFieldContext } from '../../hooks/formContext';

export type Option = { key: string; value: string };
export interface SelectFieldProps {
  title: string;
  options: Option[];
  id: string;
  onChange?: () => void;
}

export const SelectField: React.FC<SelectFieldProps> = props => {
  const { title, options, id } = props;
  const externalOnChange = props.onChange ?? (() => {});
  const field = useFieldContext<string>();

  return (
    <Box data-testid={`${id}-select-component`}>
      <Typography variant="h6" gutterBottom>
        {title}
      </Typography>

      <FormControl variant="outlined" error={!field.state.meta.isValid} fullWidth>
        <Select
          value={field.state.value}
          onChange={(e: SelectChangeEvent) => {
            field.handleChange(e.target.value as string);
            externalOnChange();
          }}
          onBlur={field.handleBlur}
          error={!field.state.meta.isValid}
          id={`${id}-dropdown`}
          inputProps={{ 'data-testid': `${id}-dropdown` }}
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
        <FormHelperText>{field.state.meta.errors?.[0]}</FormHelperText>
      </FormControl>
    </Box>
  );
};
