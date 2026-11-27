CC ?= cc
CFLAGS ?= -O2 -g
CPPFLAGS ?=
LDFLAGS ?=
LDLIBS ?= -lcrypto
STRICT = -std=c11 -Wall -Wextra -Wpedantic -Werror -D_FILE_OFFSET_BITS=64
INCLUDES = -Iinclude
SOURCES = src/cf1.c src/cf1_complete.c src/cf1_crypto.c src/cf1_usb.c src/cl1.c src/cl1_crypto.c src/j1979_can.c src/j1979_isotp.c src/j1979_live.c src/j1979_can_cli.c src/main.c
TARGET = camarodiag-native

.PHONY: all clean test install deb
all: $(TARGET)

$(TARGET): $(SOURCES) include/cf1.h include/cl1.h include/cl1_transport.h include/cl1_usb.h include/j1979_can.h include/j1979_isotp.h include/j1979_can_cli.h include/j1979_live.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) $(SOURCES) $(LDFLAGS) $(LDLIBS) -o $@

test: test-cf1 test-cu2 test-cu3 test-j1979-can test-j1979-isotp test-j1979-live test-j1979-cl1
	./test-cf1
	./test-cu2
	./test-cu3
	./test-j1979-can
	./test-j1979-isotp
	./test-j1979-live
	./test-j1979-cl1

test-cf1: tests/test_cf1.c src/cf1.c src/cf1_complete.c include/cf1.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) tests/test_cf1.c src/cf1.c src/cf1_complete.c $(LDFLAGS) -o $@

test-cu3: tests/test_cu3.c src/cl1_crypto.c src/cl1.c src/cf1.c include/cl1_transport.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) tests/test_cu3.c src/cl1_crypto.c src/cl1.c src/cf1.c $(LDFLAGS) $(LDLIBS) -o $@

test-cu2: tests/test_cu2.c src/cf1.c src/cf1_crypto.c include/cf1.h include/cf1_transport.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) tests/test_cu2.c src/cf1.c src/cf1_crypto.c $(LDFLAGS) $(LDLIBS) -o $@

test-j1979-can: tests/test_j1979_can.c src/j1979_can.c include/j1979_can.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) tests/test_j1979_can.c src/j1979_can.c $(LDFLAGS) -o $@

test-j1979-isotp: tests/test_j1979_isotp.c src/j1979_isotp.c src/j1979_can.c include/j1979_isotp.h include/j1979_can.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) tests/test_j1979_isotp.c src/j1979_isotp.c src/j1979_can.c $(LDFLAGS) -o $@


test-j1979-live: tests/test_j1979_live.c src/j1979_live.c src/j1979_can.c include/j1979_live.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) tests/test_j1979_live.c src/j1979_live.c src/j1979_can.c $(LDFLAGS) -o $@

test-j1979-cl1: tests/test_j1979_cl1.c src/cl1.c src/cf1.c include/cl1.h include/cf1.h
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(STRICT) tests/test_j1979_cl1.c src/cl1.c src/cf1.c $(LDFLAGS) -o $@

clean:
	rm -f $(TARGET) test-cf1 test-cu2 test-cu3 test-j1979-can test-j1979-isotp test-j1979-live test-j1979-cl1

PREFIX ?= /usr/local
DESTDIR ?=

install: $(TARGET)
	install -d "$(DESTDIR)$(PREFIX)/bin"
	install -m 0755 "$(TARGET)" "$(DESTDIR)$(PREFIX)/bin/$(TARGET)"

deb: $(TARGET)
	sh packaging/build-deb.sh "$(TARGET)"
