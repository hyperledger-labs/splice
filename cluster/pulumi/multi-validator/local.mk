dir := $(call current_dir)

# replace absolute paths to helm charts with relative path and sort array by (name, type)
JQ_FILTER := '(.. | .chart? | strings) |= sub("^/.*?(?=/cluster/helm/)"; "") | sort_by("\(.name)|\(.type)")'

$(dir)/test.json: cluster/helm/build

include $(dir)/../pulumi-test.mk
