import { Auth0ClusterConfig, Auth0Config, requireEnv } from 'cn-pulumi-common';

const cnAuth0Cfg: Auth0Config = {
  appToClientId: {
    validator1: 'cf0cZaTagQUN59C1HBL2udiIBdFh2CWq',
    splitwell: 'ekPlYxilradhEnpWdS80WfW63z1nHvKy',
    splitwell_validator: 'hqpZ6TP0wGyG2yYwhH6NLpuo0MpJMQZW',
    'sv-1': 'OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn',
    'sv-2': 'rv4bllgKWAiW9tBtdvURMdHW42MAXghz',
    'sv-3': 'SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk',
    'sv-4': 'CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN',
    'sv-5': 'RSgbsze3cGHipLxhPGtGy7fqtYgyefTb',
    sv1_validator: '7YEiu1ty0N6uWAjL8tCAWTNi7phr7tov',
    sv2_validator: '5N2kwYLOqrHtnnikBqw8A7foa01kui7h',
    sv3_validator: 'V0RjcwPCsIXqYTslkF5mjcJn70AiD0dh',
    sv4_validator: 'FqRozyrmu2d6dFQYC4J9uK8Y6SXCVrhL',
    sv5_validator: 'TdcDPsIwSXVw4rZmGqxl6Ifkn4neeOzW',
  },

  namespaceToUiToClientId: {
    validator1: {
      wallet: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
      cns: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
      splitwell: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
    },
    splitwell: {
      wallet: 'eeMLQ6qljnUcg9o1sJRbt4suCn2CYbSL',
      cns: 'eeMLQ6qljnUcg9o1sJRbt4suCn2CYbSL',
      splitwell: 'eeMLQ6qljnUcg9o1sJRbt4suCn2CYbSL',
    },
    'sv-1': {
      wallet: 'Ez65bly75dMqcKxQiJDF8rIP9xxkxV3J',
      cns: 'Ez65bly75dMqcKxQiJDF8rIP9xxkxV3J',
      sv: 'Ez65bly75dMqcKxQiJDF8rIP9xxkxV3J',
    },
    'sv-2': {
      wallet: 'G6Y5KYuiyOb0bnllGyQ2JKwjpwZM0Ai6',
      cns: 'G6Y5KYuiyOb0bnllGyQ2JKwjpwZM0Ai6',
      sv: 'G6Y5KYuiyOb0bnllGyQ2JKwjpwZM0Ai6',
    },
    'sv-3': {
      wallet: 'cgxHguMv32JLeew9S6wBDgNPHmPCIKaP',
      cns: 'cgxHguMv32JLeew9S6wBDgNPHmPCIKaP',
      sv: 'cgxHguMv32JLeew9S6wBDgNPHmPCIKaP',
    },
    'sv-4': {
      wallet: 'VoSuAamXhvwISHGgaCtULYmbRIWbQeTb',
      cns: 'VoSuAamXhvwISHGgaCtULYmbRIWbQeTb',
      sv: 'VoSuAamXhvwISHGgaCtULYmbRIWbQeTb',
    },
    'sv-5': {
      wallet: 'lcssSWnNbo9c0gj0SZVMncjfNJhbVaft',
      cns: 'lcssSWnNbo9c0gj0SZVMncjfNJhbVaft',
      sv: 'lcssSWnNbo9c0gj0SZVMncjfNJhbVaft',
    },
  },

  appToApiAudience: {},

  appToClientAudience: {},

  fixedTokenCacheName: 'auth0-fixed-token-cache',

  auth0Domain: 'canton-network-dev.us.auth0.com',
  auth0MgtClientId: requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_ID'),
  auth0MgtClientSecret: '',
};

const svRunbookAuth0Cfg: Auth0Config = {
  appToClientId: {
    sv: 'bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn',
    validator: 'uxeQGIBKueNDmugVs1RlMWEUZhZqyLyr',
  },

  namespaceToUiToClientId: {
    sv: {
      wallet: 'l9MS11POtbvPaVvgzns3Tdj9IDnosLwl',
      sv: '8S8o4U6OYWWuw5vPCIpFQGzzWM2IpHkx',
      cns: 'iwZgud30aDMMUYpZc5caSnjNATWwITzp',
    },
  },

  appToApiAudience: {
    participant: 'https://ledger_api.example.com', // The Ledger API in the sv-test tenant
    sv: 'https://sv.example.com/api', // The SV App API in the sv-test tenant
    validator: 'https://validator.example.com/api', // The Validator App API in the sv-test tenant
  },

  appToClientAudience: {
    sv: 'https://ledger_api.example.com',
    validator: 'https://ledger_api.example.com',
  },

  fixedTokenCacheName: 'auth0-fixed-token-cache-sv-test',

  auth0Domain: 'canton-network-sv-test.us.auth0.com',
  auth0MgtClientId: requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_ID'),
  auth0MgtClientSecret: '',
};

const validatorRunbookAuth0Cfg: Auth0Config = {
  appToClientId: {
    validator: 'cznBUeB70fnpfjaq9TzblwiwjkVyvh5z',
  },

  namespaceToUiToClientId: {
    validator: {
      wallet: '6wB6EGLJBEwaA1CfVYhRmsQG9OPcmJtj',
      cns: 'jSDSTJu00KQNwYw7XLhJfHYxgDotDXrr',
    },
  },

  appToApiAudience: {
    participant: 'https://ledger_api.example.com', // The Ledger API in the validator-test tenant
    validator: 'https://validator.example.com/api', // The Validator App API in the validator-test tenant
  },

  appToClientAudience: {
    validator: 'https://ledger_api.example.com',
  },

  fixedTokenCacheName: 'auth0-fixed-token-cache-validator-test',

  auth0Domain: 'canton-network-validator-test.us.auth0.com',
  auth0MgtClientId: requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID'),
  auth0MgtClientSecret: '',
};

export function configureAuth0(): Auth0ClusterConfig {
  return {
    cantonNetwork: cnAuth0Cfg,
    svRunbook: svRunbookAuth0Cfg,
    validatorRunbook: validatorRunbookAuth0Cfg,
  };
}
