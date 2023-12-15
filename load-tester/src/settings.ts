import { Config, configSchema } from './config';

const config: Config = configSchema.parse(JSON.parse(__ENV.EXTERNAL_CONFIG));

export default {
  ...config,
  options: {
    scenarios: {
      generate_load: {
        executor: 'constant-arrival-rate',

        // How long the test lasts
        duration: config.testDuration,

        // How many iterations per timeUnit (default = 1s)
        rate: 1,

        // Pre-allocate VUs
        preAllocatedVUs: 50,
      },
    },
  },
};
