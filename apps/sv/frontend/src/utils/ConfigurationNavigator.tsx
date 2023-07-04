import React, { useState } from 'react';

import { Button, Card, CardContent, CardHeader, Input, Stack } from '@mui/material';

import { Tuple2 } from '@daml.js/40f452260bef3f29dede136108fc08a88d5a5250310281067087da6f0baddff7/lib/DA/Types';
import { CoinConfig, USD } from '@daml.js/canton-coin-0.1.0/lib/CC/CoinConfig';
import { Schedule } from '@daml.js/canton-coin-0.1.0/lib/CC/Schedule';

import JsonEditor from './JsonEditor';
import { JSONValue } from './JsonType';

interface ComponentSwitcherProps {
  data: Schedule<string, CoinConfig<'USD'>> | undefined;
  onChange: (updatedJson: Tuple2<string, CoinConfig<'USD'>>[]) => void;
}

const ConfigurationNavigator: React.FC<ComponentSwitcherProps> = ({ data, onChange }) => {
  // @ts-ignore
  const baseComponent: Tuple2<string, CoinConfig<'USD'>> = new Tuple2<string, CoinConfig<'USD'>>();
  baseComponent._2 = data?.initialValue!;

  const initialValues: Tuple2<string, CoinConfig<'USD'>>[] = [];

  // Add initial CoinConfig along with the valid CoinConfig futureValues
  const [components, setComponents] = useState<Tuple2<string, CoinConfig<'USD'>>[]>(initialValues);

  const handleResetComponents = () => {
    setComponents([]);
  };

  const handleAddComponent = () => {
    const newComponent = baseComponent;
    newComponent._1 = date + ':00Z';
    // TODO(#6100): we should use a correct mui date picker which includes directly right format!
    if (!components.map(e => e._1).includes(newComponent._1) && /^\d{4}-/.test(newComponent._1)) {
      setComponents(prevComponents => [...prevComponents, newComponent]);
    }
  };

  const handleRemoveComponent = (index: number) => {
    setComponents(prevComponents => prevComponents.filter((_, i) => i !== index));
    onChange(components);
  };

  function addCoinConfigSchedule(coinConfig: Record<string, JSONValue>, time?: string | undefined) {
    // @ts-ignore
    const decodedNewConfig = new Tuple2<string, CoinConfig<'USD'>>();
    decodedNewConfig._1 = time;
    decodedNewConfig._2 = CoinConfig(USD).decoder.runWithException(coinConfig);

    const newComponents = components.filter(comp => comp._1 !== decodedNewConfig._1);
    newComponents.push(decodedNewConfig);
    setComponents(newComponents);
    onChange(newComponents);
  }

  const [date, setDate] = useState('');

  return (
    <Stack>
      <Input
        type="datetime-local"
        value={date}
        id={'datetime-schedule-configuration-value'}
        style={{ textAlign: 'right' }}
        onChange={e => setDate(e.target.value)}
      />
      <Button id={'button-schedule-new-configuration'} onClick={handleAddComponent}>
        Schedule New Configuration
      </Button>
      {components.map((Component, index: number) => (
        <CardComponent
          key={index}
          index={index + 1}
          datum={Component._1}
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
  index: number;
  datum: string;
  removeFunction: () => void;
}> = ({ children, index, datum, removeFunction }) => {
  const [isCollapsed, setIsCollapsed] = useState(true);

  const toggleCollapse = () => {
    setIsCollapsed(!isCollapsed);
  };

  return (
    <Card id={'card-schedule-configuration-' + index}>
      <CardHeader
        onClick={toggleCollapse}
        title={
          <React.Fragment>
            <Button id={'button-collapse-schedule-configuration-' + index} onClick={toggleCollapse}>
              {isCollapsed ? '▶' : '▼'}
            </Button>
            Schedule {index}: {datum}
            <span style={{ marginRight: '400px' }} />
            <Button id={'button-remove-schedule-configuration-' + index} onClick={removeFunction}>
              Remove
            </Button>
          </React.Fragment>
        }
      />
      {!isCollapsed && <CardContent>{children}</CardContent>}
    </Card>
  );
};

export default ConfigurationNavigator;
