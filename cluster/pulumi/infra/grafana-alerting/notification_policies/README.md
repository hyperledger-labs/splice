## Notification policies

Our alert routing is determined by the optional `team` label attached to our alerts:
- the Canton Network team gets slack notifications from all alerts **except** the ones that have the label `team = support`.
- the Support team gets only email notifications from alerts that have the labels `team = support` or  `team = da`.
