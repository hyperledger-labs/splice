import { setupServer, SetupServer } from 'msw/node';

import { Services } from '../setup/setup';
import { buildDirectoryMock } from './handlers/directory-api';
import { buildJsonApiMock } from './handlers/json-api';
import { buildScanMock } from './handlers/scan-api';
import { buildSplitwellMock } from './handlers/splitwell-api';

export const buildServer = ({ directory, jsonApi, scan, splitwell }: Services): SetupServer =>
  setupServer(
    ...buildDirectoryMock(directory.url),
    ...buildJsonApiMock(jsonApi.url),
    ...buildScanMock(scan.url),
    ...buildSplitwellMock(splitwell.url)
  );
