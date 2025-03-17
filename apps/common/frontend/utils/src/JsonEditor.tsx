// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import { Table } from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { JSONObject } from './JsonType';

interface JsonEditorProps {
  data: JSONObject;
  onChange: (updatedJson: JSONObject) => void;
}

export const JsonEditor: React.FC<JsonEditorProps> = ({ data, onChange }) => {
  const handleValueChange = (key: string, rawInputValue: string) => {
    const value = rawInputValue === '' ? null : rawInputValue;
    const keys = key.split('.');
    const lastKey = keys.pop();

    const newJson: JSONObject = { ...data };
    let nestedObject: JSONObject = newJson;
    for (const nestedKey of keys) {
      nestedObject = nestedObject[nestedKey] as JSONObject;
    }
    nestedObject[lastKey!] = value;

    onChange(newJson);
  };

  const renderJsonValue = (value: object, keyPath: string[] = []) => {
    if (typeof value === 'object' && value !== null) {
      return Object.entries(value).map(([key, nestedValue]) => (
        <React.Fragment key={key}>{renderJsonValue(nestedValue, [...keyPath, key])}</React.Fragment>
      ));
    }
    const cleanedValue = value === null ? '' : value;

    const nestedKey = keyPath.join('.');
    return (
      <TableRow>
        <TableCell align="left">
          <label id={nestedKey + '-key'}>{nestedKey}</label>
        </TableCell>
        <TableCell align="right">
          {
            <input
              type="text"
              value={cleanedValue}
              id={nestedKey + '-value'}
              data-testid={nestedKey + '-value'}
              style={{ textAlign: 'right' }}
              onChange={e => handleValueChange(nestedKey, e.target.value)}
            />
          }
        </TableCell>
      </TableRow>
    );
  };

  return (
    <>
      <Table style={{ tableLayout: 'fixed' }}>
        <TableBody>{renderJsonValue(data)}</TableBody>
      </Table>
    </>
  );
};
