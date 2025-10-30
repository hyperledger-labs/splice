// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, FormControlLabel, Radio, RadioGroup, Typography } from '@mui/material';
import { useFieldContext } from '../../hooks/formContext';
import { DesktopDateTimePicker, LocalizationProvider } from '@mui/x-date-pickers';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';
import { EffectivityType } from '../../utils/types';
import React from 'react';

export interface EffectiveDateFieldProps {
  title?: string;
  description?: string;
  initialEffectiveDate?: string;
  id: string;
}

export const EffectiveDateField: React.FC<EffectiveDateFieldProps> = props => {
  const { initialEffectiveDate, id } = props;
  const title = props.title ? props.title : 'Vote Proposal Effectivity';
  const description = props.description
    ? props.description
    : 'Select the date and time the proposal will take effect';

  const field = useFieldContext<{
    type: EffectivityType;
    effectiveDate: string | undefined;
  }>();

  const currentType = field.state.value?.type || 'custom';

  const handleTypeChange = (type: EffectivityType) => {
    if (type === 'custom') {
      const currentDate = field.state.value?.effectiveDate
        ? dayjs(field.state.value.effectiveDate)
        : dayjs(initialEffectiveDate);

      field.handleChange({
        type: 'custom',
        effectiveDate: currentDate.format(dateTimeFormatISO),
      });
    } else {
      field.handleChange({
        type: 'threshold',
        effectiveDate: undefined,
      });
    }
  };

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <Typography variant="h5" gutterBottom>
        {title}
      </Typography>

      <RadioGroup
        value={currentType}
        onChange={e => handleTypeChange(e.target.value as EffectivityType)}
      >
        <FormControlLabel
          value="custom"
          control={<Radio />}
          label={<Typography>Custom</Typography>}
        />

        {currentType === 'custom' && (
          <>
            <Typography variant="body2" color="text.secondary" gutterBottom>
              {description}
            </Typography>

            <LocalizationProvider dateAdapter={AdapterDayjs}>
              <DesktopDateTimePicker
                value={dayjs(field.state.value.effectiveDate)}
                format={dateTimeFormatISO}
                onChange={newDate => {
                  field.handleChange({
                    type: 'custom',
                    effectiveDate: newDate?.format(dateTimeFormatISO) || undefined,
                  });
                }}
                enableAccessibleFieldDOMStructure={false}
                slotProps={{
                  textField: {
                    fullWidth: true,
                    variant: 'outlined',
                    id: `${id}-field`,
                    onBlur: field.handleBlur,
                    error: !field.state.meta.isValid,
                    helperText: field.state.meta.errors?.[0],
                    inputProps: {
                      'data-testid': `${id}-field`,
                    },
                  },
                }}
              />
            </LocalizationProvider>
          </>
        )}

        <FormControlLabel
          value="threshold"
          control={<Radio />}
          label={
            <Box>
              <Typography>Make effective at threshold</Typography>
              <Typography variant="body2" color="text.secondary">
                This will allow the vote proposal to take effect immediately when 2/3 vote in favor
              </Typography>
            </Box>
          }
          sx={{ mt: 2 }}
        />
      </RadioGroup>
    </Box>
  );
};
