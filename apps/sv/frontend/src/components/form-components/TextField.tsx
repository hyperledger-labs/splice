// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  Box,
  TextField as MuiTextField,
  TextFieldProps as MuiTextFieldProps,
  Typography,
} from '@mui/material';
import {
  CREATE_PROPOSAL_FIELD_HELPER_SX,
  CREATE_PROPOSAL_FIELD_LABEL_SX,
} from '../../constants/createProposalLayout';
import { useFieldContext } from '../../hooks/formContext';
import { scrollableTextFieldSx } from '../ui/identifierStyles';
import { singleLineFieldSx } from '../../themes/fieldStyles';

export interface TextFieldProps {
  id: string;
  title: string;
  subtitle?: string;
  scrollableIdentifier?: boolean;
  muiTextFieldProps?: MuiTextFieldProps;
  onChange?: (value: string) => void;
  onBlur?: () => void;
}

export const TextField: React.FC<TextFieldProps> = props => {
  const {
    title,
    subtitle,
    id,
    scrollableIdentifier = false,
    muiTextFieldProps,
    onChange,
    onBlur,
  } = props;
  const field = useFieldContext<string>();
  return (
    <Box>
      <Typography
        component="p"
        id={`${id}-title`}
        data-testid={`${id}-title`}
        sx={{ ...CREATE_PROPOSAL_FIELD_LABEL_SX, mb: 1 }}
      >
        {title}
      </Typography>

      <MuiTextField
        fullWidth
        variant="outlined"
        autoComplete="off"
        value={field.state.value}
        onBlur={() => {
          field.handleBlur();
          onBlur?.();
        }}
        error={!field.state.meta.isValid}
        helperText={
          <Typography
            component="span"
            id={`${id}-error`}
            data-testid={`${id}-error`}
            sx={{ ...CREATE_PROPOSAL_FIELD_HELPER_SX, color: 'inherit' }}
          >
            {field.state.meta.errors?.[0]}
          </Typography>
        }
        onChange={e => {
          field.handleChange(e.target.value);
          onChange?.(e.target.value);
        }}
        inputProps={{ 'data-testid': id }}
        id={id}
        sx={
          scrollableIdentifier
            ? theme => ({
                ...(typeof singleLineFieldSx === 'function'
                  ? singleLineFieldSx(theme)
                  : singleLineFieldSx),
                ...scrollableTextFieldSx,
              })
            : singleLineFieldSx
        }
        {...muiTextFieldProps}
      />
      {subtitle && (
        <Typography
          component="p"
          data-testid={`${id}-subtitle`}
          sx={{ ...CREATE_PROPOSAL_FIELD_HELPER_SX, mt: 1 }}
        >
          {subtitle}
        </Typography>
      )}
    </Box>
  );
};
