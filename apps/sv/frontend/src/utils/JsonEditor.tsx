import React, { useState } from 'react';

import { Button, Table } from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { JSONValue } from './JsonType';

interface JsonEditorProps {
  data: Record<string, JSONValue>;
  onChange: (updatedJson: Record<string, JSONValue>) => void;
}

const JsonEditor: React.FC<JsonEditorProps> = ({ data, onChange }) => {
  const [json, setJson] = useState(data);

  const handleValueChange = (key: string, value: JSONValue) => {
    const keys = key.split('.');
    const lastKey = keys.pop();

    let nestedObject: Record<string, JSONValue> = json;
    for (const nestedKey of keys) {
      nestedObject = nestedObject[nestedKey] as Record<string, JSONValue>;
    }

    // @ts-ignore
    nestedObject[lastKey!] = value;
    setJson(previousJson => ({ ...previousJson }));
  };

  const handleSaveClick = () => {
    console.log('click');
    onChange(json);
  };

  const renderJsonValue = (value: object, keyPath: string[] = []) => {
    if (typeof value === 'object' && value !== null) {
      return Object.entries(value).map(([key, nestedValue]) => (
        <React.Fragment key={key}>{renderJsonValue(nestedValue, [...keyPath, key])}</React.Fragment>
      ));
    }

    const nestedKey = keyPath.join('.');
    return (
      <TableRow>
        <TableCell align="left">
          <label id={nestedKey + '-key'}>{nestedKey}: </label>
        </TableCell>
        <TableCell align="right">
          <input
            type="text"
            value={value}
            id={nestedKey + '-value'}
            style={{ textAlign: 'right' }}
            onChange={e => handleValueChange(nestedKey, e.target.value)}
          />
        </TableCell>
      </TableRow>
    );
  };

  return (
    <>
      <Table style={{ tableLayout: 'fixed' }} className="sv-coin-price-table">
        <TableBody>{renderJsonValue(json)}</TableBody>
      </Table>
      <Button id="update-json-submit-button" fullWidth size="large" onClick={handleSaveClick}>
        Update Configuration Proposition
      </Button>
    </>
  );
};

export default JsonEditor;
