// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  JSONValue,
  JsonEditor,
  getUTCWithOffset,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useState } from 'react';

import { Box, Button, Card, CardContent, CardHeader, Stack, Typography } from '@mui/material';
import { DesktopDateTimePicker } from '@mui/x-date-pickers/DesktopDateTimePicker';

import { Tuple2 } from '@daml.js/5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4/lib/DA/Types';
import { AmuletConfig, USD } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { Schedule } from '@daml.js/splice-amulet/lib/Splice/Schedule';

dayjs.extend(utc);

interface ComponentSwitcherProps {
  data: Schedule<string, AmuletConfig<'USD'>> | undefined;
  onChange: (updatedJson: Schedule<string, AmuletConfig<'USD'>>) => void;
}

const ConfigurationNavigator: React.FC<ComponentSwitcherProps> = ({ data, onChange }) => {
  // use to schedule new configuration (futureValues)
  const baseComponent: Tuple2<string, AmuletConfig<'USD'>> = {
    _1: '',
    _2: data?.initialValue!,
  };

  const [initialComponent, setInitialComponent] = useState<AmuletConfig<'USD'>>(
    data?.initialValue!
  );
  const [components, setComponents] = useState<Tuple2<string, AmuletConfig<'USD'>>[]>(
    data?.futureValues!
  );

  async function setDecodedInitialComponent(dsoRulesConfig: Record<string, JSONValue>) {
    const decodedConfig = AmuletConfig(USD).decoder.runWithException(dsoRulesConfig);
    setInitialComponent(decodedConfig);
    onChange({ initialValue: initialComponent, futureValues: components });
  }

  const handleResetComponents = () => {
    setComponents([]);
  };

  const handleAddComponent = () => {
    if (dayjs(date).isBefore(dayjs.utc())) {
      return;
    }

    const newComponent = baseComponent;
    newComponent._1 = dayjs.utc(date).format('YYYY-MM-DDTHH:mm:00[Z]');

    if (!components.map(e => e._1).includes(newComponent._1)) {
      setComponents(prevComponents =>
        [...prevComponents, newComponent].sort((a, b) => {
          return parseInt(a._1) - parseInt(b._1);
        })
      );
    }
  };

  const handleRemoveComponent = (index: number) => {
    setComponents(prevComponents => prevComponents.filter((_, i) => i !== index));
    onChange({ initialValue: initialComponent, futureValues: components });
  };

  function addAmuletConfigSchedule(
    amuletConfig: Record<string, JSONValue>,
    time?: string | undefined
  ) {
    components.filter(comp => comp._1 === time)[0]._2 =
      AmuletConfig(USD).decoder.runWithException(amuletConfig);
    onChange({ initialValue: initialComponent, futureValues: components });
  }

  const [date, setDate] = useState<Dayjs | null>(dayjs());

  return (
    <Stack>
      <DesktopDateTimePicker
        label={`Enter time in local timezone (${getUTCWithOffset()})`}
        value={date}
        minDate={dayjs()}
        readOnly={false}
        onChange={(newValue: React.SetStateAction<Dayjs | null>) => setDate(newValue)}
        slotProps={{
          textField: {
            id: 'datetime-picker-amulet-configuration',
          },
        }}
        closeOnSelect
      />
      <Button id={'button-schedule-new-configuration'} onClick={handleAddComponent}>
        Schedule New Configuration
      </Button>
      <CardComponent key={0}>
        <JsonEditor
          data={AmuletConfig(USD).encode(initialComponent) as Record<string, JSONValue>}
          onChange={setDecodedInitialComponent}
        />
      </CardComponent>
      {components.map((Component, index: number) => (
        <CardComponent
          key={index + 1}
          index={index + 1}
          date={dayjs(Component._1).toString().replace('GMT', 'UTC')}
          removeFunction={() => handleRemoveComponent(index)}
        >
          <JsonEditor
            data={AmuletConfig(USD).encode(Component._2) as Record<string, JSONValue>}
            onChange={newJson => addAmuletConfigSchedule(newJson, Component._1)}
          />
        </CardComponent>
      ))}
      <Button id={'button-reset-schedules'} onClick={handleResetComponents}>
        Reset Schedules
      </Button>
    </Stack>
  );
};

const CardComponent: React.FC<{
  children: React.ReactNode;
  index?: number;
  date?: string;
  removeFunction?: () => void;
}> = ({ children, index, date, removeFunction }) => {
  const [isCollapsed, setIsCollapsed] = useState(true);

  const toggleCollapse = () => {
    setIsCollapsed(!isCollapsed);
  };

  return (
    <Card id={'card-schedule-configuration-' + index}>
      <CardHeader
        onClick={toggleCollapse}
        title={
          <Box style={{ display: 'flex', alignItems: 'center' }}>
            <Button id={'button-collapse-schedule-configuration-' + index} onClick={toggleCollapse}>
              {isCollapsed ? '▶' : '▼'}
            </Button>
            <Typography style={{ flex: 1, marginRight: '10px' }}>
              {index ? `Schedule ${index}: ${date}` : 'Current Configuration'}
            </Typography>
            {removeFunction && (
              <Button id={'button-remove-schedule-configuration-' + index} onClick={removeFunction}>
                Remove
              </Button>
            )}
          </Box>
        }
      />
      {!isCollapsed && <CardContent>{children}</CardContent>}
    </Card>
  );
};

export default ConfigurationNavigator;
