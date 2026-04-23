## Notification policies

Our alert routing is determined by the optional `team` label attached to our alerts:
- the Canton Network team gets slack notifications from all alerts **except** the ones that have the label `team = support`.
- the Support team gets only email notifications from alerts that have the labels `team = support` or  `team = da`.

### Mute intervals

Mute time intervals suppress notifications for matching alerts during specified time windows.
They are configured in the `monitoring.alerting.muteTimeIntervals` field in the cluster config.
Each interval inherits the default notification channel, specifies a name, some Grafana label matchers, a UTC time range, and an optional list of weekdays.
If `weekdays` is omitted, the mute applies every day.

See [official grafana docs](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/mute-timings/) for more info.

For example, mute all `Health of a connection` alerts in the `sv` and `sv-1` namespace from 04:00 to 07:00 UTC on weekdays:

```yaml
monitoring:
  alerting:
    muteTimeIntervals:
      - name: sv-daily-mute
        objectMatchers:
          - ['namespace', '=~', 'sv|sv-1']
          - ['alertname', '=', 'Health of a connection']
        startTime: '04:00'
        endTime: '07:00'
        weekdays:         
          - monday:friday
