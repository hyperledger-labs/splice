import React from 'react';

import { FormControl, FormControlLabel, FormGroup, FormHelperText, Switch } from '@mui/material';

import { EnabledChoices } from '@daml.js/canton-coin-api-0.1.0/lib/CC/API/V1/Coin';

interface ComponentSwitcherProps {
  data: EnabledChoices;
  isDevNet: boolean;
  onChange: (updatedChoices: EnabledChoices) => void;
}

const SwitchGroupComponent: React.FC<ComponentSwitcherProps> = ({ data, isDevNet, onChange }) => {
  const [choices, setChoices] = React.useState(data);

  const handleChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setChoices(prevChoices => ({
      ...prevChoices,
      [event.target.name]: event.target.checked,
    }));
  };

  React.useEffect(() => {
    onChange(choices);
  }, [choices, onChange]);

  return (
    <FormControl component="fieldset" variant="standard">
      <FormGroup id={'switch-group-enabled-choices'}>
        {Object.keys(data)
          .filter(key => (isDevNet ? true : !key.includes('DevNet')))
          .map(key => (
            <FormControlLabel
              key={key}
              control={
                // @ts-ignore
                <Switch checked={choices[key]} onChange={handleChange} name={key} id={'switch'} />
              }
              label={key.replaceAll('_', ' ')}
            />
          ))}
      </FormGroup>
      <FormHelperText>Slide right to enable</FormHelperText>
    </FormControl>
  );
};

export default SwitchGroupComponent;
