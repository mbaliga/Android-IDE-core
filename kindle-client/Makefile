CC ?= cc
CFLAGS ?= -O2 -pipe
CFLAGS += -std=c11 -Wall -Wextra -Werror -D_FILE_OFFSET_BITS=64 -Isrc
LDFLAGS ?=
LDLIBS ?= -lz -pthread

BUILD := build
COMMON := $(BUILD)/protocol.o $(BUILD)/sha256.o

.PHONY: all clean test

all: $(BUILD)/workdeck-client

$(BUILD):
	mkdir -p $(BUILD)

$(BUILD)/%.o: src/%.c | $(BUILD)
	$(CC) $(CFLAGS) -c $< -o $@

$(BUILD)/workdeck-client: $(BUILD)/workdeck_client.o $(COMMON)
	$(CC) $(LDFLAGS) $^ $(LDLIBS) -o $@

$(BUILD)/protocol-test: tests/protocol_test.c $(COMMON)
	$(CC) $(CFLAGS) $(LDFLAGS) $^ -pthread -o $@

test: $(BUILD)/protocol-test
	$(BUILD)/protocol-test

clean:
	rm -rf $(BUILD)
