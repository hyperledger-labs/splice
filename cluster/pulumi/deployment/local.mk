dir := $(call current_dir)

# sort array by (name, type)
JQ_FILTER := 'sort_by("\(.name)|\(.type)")'

include $(dir)/../pulumi-test.mk
