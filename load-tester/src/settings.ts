import { Config, configSchema } from './config';

const config: Config = configSchema.parse(JSON.parse(__ENV.EXTERNAL_CONFIG));

export default {
  ...config,
  options: {
    vus: 10,
    duration: config.testDuration,
  },
};
