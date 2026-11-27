CC ?= cc
CFLAGS ?= -O2 -g
CPPFLAGS ?=
LDFLAGS ?=
LDLIBS ?= -lcrypto
STRICT = -std=c11 -Wall -Wextra -Wpedantic -Werror -D_FILE_OFFSET_BITS=64
INCLUDES = -Iinclude
SOURCES = src/cf1.c src/cf1_complete.c src/main.c
TARGET = camarodiag-native

.PHONY: all clean test
all: $(TARGET)

$(TARGET): $(SOURCES) include/cf1.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) $(SOURCES) $(LDFLAGS) $(LDLIBS) -o $@

test: test-cf1
	./test-cf1

test-cf1: tests/test_cf1.c src/cf1.c src/cf1_complete.c include/cf1.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) tests/test_cf1.c src/cf1.c src/cf1_complete.c $(LDFLAGS) -o $@

clean:
	rm -f $(TARGET) test-cf1
