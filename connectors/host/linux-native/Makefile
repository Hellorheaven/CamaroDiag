CC ?= cc
CFLAGS ?= -O2 -g
CPPFLAGS ?=
LDFLAGS ?=
LDLIBS ?= -lcrypto
STRICT = -std=c11 -Wall -Wextra -Wpedantic -Werror -D_FILE_OFFSET_BITS=64
INCLUDES = -Iinclude
SOURCES = src/cf1.c src/cf1_complete.c src/cf1_crypto.c src/cf1_usb.c src/main.c
TARGET = camarodiag-native

.PHONY: all clean test install deb
all: $(TARGET)

$(TARGET): $(SOURCES) include/cf1.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) $(SOURCES) $(LDFLAGS) $(LDLIBS) -o $@

test: test-cf1 test-cu2
	./test-cf1
	./test-cu2

test-cf1: tests/test_cf1.c src/cf1.c src/cf1_complete.c include/cf1.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) tests/test_cf1.c src/cf1.c src/cf1_complete.c $(LDFLAGS) -o $@

test-cu2: tests/test_cu2.c src/cf1.c src/cf1_crypto.c include/cf1.h include/cf1_transport.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) tests/test_cu2.c src/cf1.c src/cf1_crypto.c $(LDFLAGS) $(LDLIBS) -o $@

clean:
	rm -f $(TARGET) test-cf1 test-cu2

PREFIX ?= /usr/local
DESTDIR ?=

install: $(TARGET)
	install -d "$(DESTDIR)$(PREFIX)/bin"
	install -m 0755 "$(TARGET)" "$(DESTDIR)$(PREFIX)/bin/$(TARGET)"

deb: $(TARGET)
	sh packaging/build-deb.sh "$(TARGET)"
