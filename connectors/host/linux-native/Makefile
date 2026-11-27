# CamaroDiag Linux native bridge: new J1979 decoding is C++20.
# Legacy C transport and capture files remain temporarily ABI-compatible;
# their replacement must use C++, with no new Python or C implementation.
CC ?= cc
CXX ?= c++
CFLAGS ?= -O2 -g
CXXFLAGS ?= -O2 -g
CPPFLAGS ?=
LDFLAGS ?=
LDLIBS ?= -lcrypto
CSTRICT = -std=c11 -Wall -Wextra -Wpedantic -Werror -D_FILE_OFFSET_BITS=64
CXXSTRICT = -std=c++20 -Wall -Wextra -Wpedantic -Werror -D_FILE_OFFSET_BITS=64
INCLUDES = -Iinclude -I../../firmware/shared
TARGET = camarodiag-native

LEGACY_C_SOURCES = src/cf1.c src/cf1_usb.c src/main.c
CXX_SOURCES = src/cf1_complete.cpp src/cf1_crypto.cpp src/cl1.cpp src/cl1_crypto.cpp src/j1979_policy.cpp src/j1979_monitor_scaling.cpp src/j1979_vehicle_info.cpp src/j1979_measurement.cpp src/j1979_can.cpp src/j1979_live.cpp src/j1979_isotp.cpp src/j1979_can_cli.cpp
LEGACY_C_OBJECTS = $(patsubst src/%.c,build/%.o,$(LEGACY_C_SOURCES))
CXX_OBJECTS = $(patsubst src/%.cpp,build/%.o,$(CXX_SOURCES))
DECODER_OBJECTS = build/j1979_monitor_scaling.o build/j1979_vehicle_info.o build/j1979_measurement.o

.PHONY: all clean test install deb
all: $(TARGET)

build:
	mkdir -p $@

build/%.o: src/%.c | build
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(CSTRICT) -c $< -o $@

build/%.o: src/%.cpp | build
	$(CXX) $(CPPFLAGS) $(INCLUDES) $(CXXFLAGS) $(CXXSTRICT) -c $< -o $@

build/test_%.o: tests/test_%.c | build
	$(CC) $(CPPFLAGS) $(INCLUDES) $(CFLAGS) $(CSTRICT) -c $< -o $@

build/test_%.o: tests/test_%.cpp | build
	$(CXX) $(CPPFLAGS) $(INCLUDES) $(CXXFLAGS) $(CXXSTRICT) -c $< -o $@

$(TARGET): $(LEGACY_C_OBJECTS) $(CXX_OBJECTS)
	$(CXX) $(CXXFLAGS) $(CXXSTRICT) $^ $(LDFLAGS) $(LDLIBS) -o $@

test: test-j1979-measurement test-j1979-monitor test-j1979-vehicle-info test-j1979-native-c test-cf1 test-cu2 test-cu3 test-j1979-can test-j1979-isotp test-j1979-live test-j1979-cl1
	./test-j1979-measurement
	./test-j1979-monitor
	./test-j1979-vehicle-info
	./test-j1979-native-c
	./test-cf1
	./test-cu2
	./test-cu3
	./test-j1979-can
	./test-j1979-isotp
	./test-j1979-live
	./test-j1979-cl1

test-j1979-measurement: build/test_j1979_measurement.o build/j1979_measurement.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-vehicle-info: build/test_j1979_vehicle_info.o build/j1979_vehicle_info.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-monitor: build/test_j1979_monitor_scaling.o build/j1979_monitor_scaling.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-native-c: build/test_j1979_native_c.o build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-cf1: build/test_cf1.o build/cf1.o build/cf1_complete.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-cu3: build/test_cu3.o build/cl1_crypto.o build/cl1.o build/cf1.o
	$(CXX) $(LDFLAGS) $^ $(LDLIBS) -o $@

test-cu2: build/test_cu2.o build/cf1.o build/cf1_crypto.o
	$(CXX) $(LDFLAGS) $^ $(LDLIBS) -o $@

test-j1979-can: build/test_j1979_can.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-isotp: build/test_j1979_isotp.o build/j1979_isotp.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-live: build/test_j1979_live.o build/j1979_live.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-cl1: build/test_j1979_cl1.o build/cl1.o build/cf1.o
	$(CXX) $(LDFLAGS) $^ -o $@

clean:
	rm -rf build
	rm -f $(TARGET) test-j1979-measurement test-j1979-monitor test-j1979-vehicle-info test-j1979-native-c test-cf1 test-cu2 test-cu3 test-j1979-can test-j1979-isotp test-j1979-live test-j1979-cl1

PREFIX ?= /usr/local
DESTDIR ?=

install: $(TARGET)
	install -d "$(DESTDIR)$(PREFIX)/bin"
	install -m 0755 "$(TARGET)" "$(DESTDIR)$(PREFIX)/bin/$(TARGET)"

deb: $(TARGET)
	sh packaging/build-deb.sh "$(TARGET)"
