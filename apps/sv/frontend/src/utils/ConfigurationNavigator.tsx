import { JSONValue, JsonEditor, getUTCWithOffset } from 'common-frontend';
import { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useState } from 'react';

import { Box, Button, Card, CardContent, CardHeader, Stack, Typography } from '@mui/material';
import { DesktopDateTimePicker } from '@mui/x-date-pickers/DesktopDateTimePicker';

import { Tuple2 } from '@daml.js/40f452260bef3f29dede136108fc08a88d5a5250310281067087da6f0baddff7/lib/DA/Types';
import { CoinConfig, USD } from '@daml.js/canton-coin/lib/CC/CoinConfig';
import { Schedule } from '@daml.js/canton-coin/lib/CC/Schedule';

dayjs.extend(utc);

interface ComponentSwitcherProps {
  data: Schedule<string, CoinConfig<'USD'>> | undefined;
  onChange: (updatedJson: Schedule<string, CoinConfig<'USD'>>) => void;
}

const ConfigurationNavigator: React.FC<ComponentSwitcherProps> = ({ data, onChange }) => {
  // use to schedule new configuration (futureValues)
  const baseComponent: Tuple2<string, CoinConfig<'USD'>> = {
    _1: '',
    _2: data?.initialValue!,
  };

  const [initialComponent, setInitialComponent] = useState<CoinConfig<'USD'>>(data?.initialValue!);
  const [components, setComponents] = useState<Tuple2<string, CoinConfig<'USD'>>[]>(
    data?.futureValues!
  );

  async function setDecodedInitialComponent(svcRulesConfig: Record<string, JSONValue>) {
    const decodedConfig = CoinConfig(USD).decoder.runWithException(svcRulesConfig);
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

  function addCoinConfigSchedule(coinConfig: Record<string, JSONValue>, time?: string | undefined) {
    components.filter(comp => comp._1 === time)[0]._2 =
      CoinConfig(USD).decoder.runWithException(coinConfig);
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
            id: 'datetime-picker-coin-configuration',
          },
        }}
        closeOnSelect
      />
      <Button id={'button-schedule-new-configuration'} onClick={handleAddComponent}>
        Schedule New Configuration
      </Button>
      <CardComponent key={0}>
        <JsonEditor
          data={CoinConfig(USD).encode(initialComponent) as Record<string, JSONValue>}
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
            data={CoinConfig(USD).encode(Component._2) as Record<string, JSONValue>}
            time={Component._1}
            onChange={addCoinConfigSchedule}
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
