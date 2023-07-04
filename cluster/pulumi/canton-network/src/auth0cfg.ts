import { Auth0Config, requireEnv } from 'cn-pulumi-common';

const auth0Account = 'canton-network-dev.us';

export const auth0Cfg: Auth0Config = {
  appToClientId: {
    validator1: 'cf0cZaTagQUN59C1HBL2udiIBdFh2CWq',
    splitwell: 'ekPlYxilradhEnpWdS80WfW63z1nHvKy',
    splitwell_validator: 'hqpZ6TP0wGyG2yYwhH6NLpuo0MpJMQZW',
    'sv-1': 'OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn',
    'sv-2': 'rv4bllgKWAiW9tBtdvURMdHW42MAXghz',
    'sv-3': 'SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk',
    'sv-4': 'CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN',
    sv1_validator: '7YEiu1ty0N6uWAjL8tCAWTNi7phr7tov',
    sv2_validator: '5N2kwYLOqrHtnnikBqw8A7foa01kui7h',
    sv3_validator: 'V0RjcwPCsIXqYTslkF5mjcJn70AiD0dh',
    sv4_validator: 'FqRozyrmu2d6dFQYC4J9uK8Y6SXCVrhL',
  },

  namespaceToUiClientId: {
    validator1: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
    splitwell: 'eeMLQ6qljnUcg9o1sJRbt4suCn2CYbSL',
    'sv-1': 'Ez65bly75dMqcKxQiJDF8rIP9xxkxV3J',
    'sv-2': 'G6Y5KYuiyOb0bnllGyQ2JKwjpwZM0Ai6',
    'sv-3': 'cgxHguMv32JLeew9S6wBDgNPHmPCIKaP',
    'sv-4': 'VoSuAamXhvwISHGgaCtULYmbRIWbQeTb',
  },

  appToApiAudience: {},

  appToClientAudience: {},

  fixedTokenCacheName: 'auth0-fixed-token-cache',

  // TODO(#5836): the naming we have for this vs those for sv-runbook tenant is terrible!!
  auth0Domain: `${auth0Account}.auth0.com`,
  auth0MgtClientId: requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_ID'),
  auth0MgtClientSecret: requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET'),
};
