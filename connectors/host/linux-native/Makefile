# CamaroDiag Linux native bridge: C++20 for product and tests.
CXX ?= c++
CXXFLAGS ?= -O2 -g
CPPFLAGS ?=
LDFLAGS ?=
LDLIBS ?= -lcrypto
CXXSTRICT = -std=c++20 -Wall -Wextra -Wpedantic -Werror -D_FILE_OFFSET_BITS=64
INCLUDES = -Iinclude -I../../firmware/shared
TARGET = camarodiag-native

CXX_SOURCES = src/j1979_legacy_scan_history.cpp src/j1979_legacy_scan_history_summary.cpp src/j1979_native_live_inspection.cpp src/j1979_native_can_inspection.cpp src/j1979_historical_can_derived.cpp src/j1979_historical_can_summary.cpp src/j1979_historical_can_plan.cpp src/j1979_network_history.cpp src/j1979_legacy_discovery_binding.cpp src/j1979_legacy_discovery_history.cpp src/main.cpp src/j1979_legacy_journal.cpp src/j1979_host_freeze.cpp src/j1979_elm_can_campaign_wire.cpp src/j1979_elm_can_campaign.cpp src/j1979_elm_can_journal.cpp src/j1979_elm_can_trace_wire.cpp src/j1979_elm_can_trace.cpp src/j1979_elm_can_report.cpp src/j1979_elm_can_session.cpp src/j1979_elm_can_decode.cpp src/j1979_host_maintenance_report.cpp src/j1979_host_maintenance_elm.cpp src/j1979_host_maintenance_socket.cpp src/j1979_host_maintenance_storage.cpp src/native_json.cpp src/j1979_catalogue_report.cpp src/j1979_catalogue_projection.cpp src/j1979_catalogue.cpp src/j1979_catalogue_exact.cpp src/j1979_legacy_shape.cpp src/j1979_legacy_info.cpp src/j1979_legacy_monitor.cpp src/j1979_legacy_report.cpp src/j1979_legacy_mutation_report.cpp src/j1979_can_history_report.cpp src/j1979_can_history.cpp src/j1979_legacy_mutation_inspection.cpp src/j1979_legacy_mutation_parse.cpp src/j1979_legacy_mutation.cpp src/j1979_legacy_scan_wire.cpp src/j1979_legacy_scan.cpp src/j1979_discovery.cpp src/j1979_elm.cpp src/j1979_legacy_freeze_report.cpp src/j1979_legacy_freeze_wire.cpp src/j1979_legacy_freeze_acquire.cpp src/j1979_legacy_freeze.cpp src/j1979_legacy_live_wire.cpp src/j1979_legacy_live_report.cpp src/j1979_legacy_live.cpp src/j1979_legacy_live_plan.cpp src/j1979_legacy_identity.cpp src/j1979_legacy_freeze_plan.cpp src/j1979_legacy.cpp src/j1979_cu1.cpp src/j1979_cj1.cpp src/j1979_cu45_report.cpp src/j1979_cu45_serial.cpp src/j1979_cu45_session.cpp src/j1979_cu45.cpp src/j1979_cm1.cpp src/cf1_usb.cpp src/cf1.cpp src/cf1_complete.cpp src/cf1_crypto.cpp src/cl1.cpp src/cl1_crypto.cpp src/j1979_policy.cpp src/j1979_monitor_scaling.cpp src/j1979_vehicle_info.cpp src/j1979_measurement.cpp src/j1979_projection.cpp src/j1979_status.cpp src/j1979_multi.cpp src/j1979_context.cpp src/j1979_can_network_report.cpp src/j1979_can_network_socket.cpp src/j1979_can_network_scan.cpp src/j1979_can_network.cpp src/j1979_can.cpp src/j1979_live.cpp src/j1979_isotp.cpp src/j1979_can_cli.cpp
CXX_OBJECTS = $(patsubst src/%.cpp,build/%.o,$(CXX_SOURCES))
DECODER_OBJECTS = build/j1979_projection.o build/j1979_status.o build/j1979_multi.o build/j1979_context.o build/j1979_monitor_scaling.o build/j1979_vehicle_info.o build/j1979_measurement.o

.PHONY: all clean test install deb
all: $(TARGET)

build:
	mkdir -p $@

build/%.o: src/%.cpp | build
	$(CXX) $(CPPFLAGS) $(INCLUDES) $(CXXFLAGS) $(CXXSTRICT) -c $< -o $@

build/test_%.o: tests/test_%.cpp | build
	$(CXX) $(CPPFLAGS) $(INCLUDES) $(CXXFLAGS) $(CXXSTRICT) -c $< -o $@

$(TARGET): $(CXX_OBJECTS)
	$(CXX) $(CXXFLAGS) $(CXXSTRICT) $^ $(LDFLAGS) $(LDLIBS) -o $@

test: test-j1979-legacy-scan-history test-j1979-native-can-inspection test-j1979-historical-can-network test-j1979-historical-can-plan test-native-json-report test-j1979-network-history test-j1979-legacy-discovery-history test-j1979-can-history test-j1979-elm-campaign test-j1979-elm-journal test-j1979-legacy-decoding test-j1979-legacy-journal test-j1979-host-freeze test-j1979-elm-can test-j1979-host-maintenance test-j1979-catalogue test-j1979-can-network test-j1979-legacy-mutation test-j1979-legacy-live test-j1979-legacy-freeze test-j1979-legacy-scan test-j1979-legacy test-j1979-projection test-cu1 test-j1979-cj1 test-j1979-cm1 test-j1979-context test-j1979-measurement test-j1979-monitor test-j1979-vehicle-info test-j1979-native-c test-cf1 test-cu2 test-cu3 test-j1979-can test-j1979-isotp test-j1979-live test-j1979-cl1
	./test-j1979-legacy-scan-history
	./test-j1979-native-can-inspection
	./test-j1979-historical-can-network
	./test-j1979-historical-can-plan
	./test-native-json-report
	./test-j1979-network-history
	./test-j1979-legacy-discovery-history
	./test-j1979-can-history
	./test-j1979-elm-campaign
	./test-j1979-elm-journal
	./test-j1979-legacy-decoding
	./test-j1979-legacy-journal
	./test-j1979-host-freeze
	./test-j1979-elm-can
	./test-j1979-host-maintenance
	./test-j1979-catalogue
	./test-j1979-can-network
	./test-j1979-legacy-mutation
	./test-j1979-legacy-live
	./test-j1979-legacy-freeze
	./test-j1979-legacy-scan
	./test-j1979-legacy
	./test-j1979-projection
	./test-cu1
	./test-j1979-cj1
	./test-j1979-cm1
	./test-j1979-context
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

test-j1979-legacy-scan-history: build/test_j1979_legacy_scan_history.o build/j1979_legacy_scan_history.o build/j1979_legacy_scan_history_summary.o build/j1979_legacy_info.o build/j1979_legacy_identity.o build/j1979_legacy_shape.o build/j1979_legacy_monitor.o build/j1979_legacy.o build/j1979_legacy_freeze_plan.o build/j1979_elm.o build/j1979_historical_can_derived.o build/native_json.o build/j1979_policy.o $(DECODER_OBJECTS)
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-native-can-inspection: build/test_j1979_native_can_inspection.o build/j1979_native_can_inspection.o build/native_json.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o build/j1979_network_history.o build/j1979_can_network.o build/j1979_can_network_report.o build/j1979_historical_can_plan.o build/j1979_historical_can_summary.o build/j1979_historical_can_derived.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-historical-can-plan: build/test_j1979_historical_can_plan.o build/j1979_historical_can_plan.o build/j1979_historical_can_summary.o build/j1979_historical_can_derived.o $(DECODER_OBJECTS) build/native_json.o build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-native-json-report: build/test_native_json_report.o build/native_json.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-network-history: build/test_j1979_network_history.o build/j1979_network_history.o build/j1979_can_network.o build/j1979_can_network_report.o build/j1979_historical_can_plan.o build/j1979_historical_can_summary.o build/j1979_historical_can_derived.o $(DECODER_OBJECTS) build/j1979_policy.o build/native_json.o build/j1979_native_can_inspection.o build/j1979_can.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-historical-can-network: build/test_j1979_historical_can_network.o build/j1979_network_history.o build/j1979_can_network.o build/j1979_can_network_report.o build/j1979_historical_can_plan.o build/j1979_historical_can_summary.o build/j1979_historical_can_derived.o $(DECODER_OBJECTS) build/j1979_policy.o build/native_json.o build/j1979_native_can_inspection.o build/j1979_can.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-legacy-discovery-history: build/test_j1979_legacy_discovery_history.o build/j1979_legacy_discovery_history.o build/native_json.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-legacy-decoding: build/test_j1979_legacy_decoding.o build/j1979_legacy_shape.o build/j1979_legacy_info.o build/j1979_legacy_monitor.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-legacy-journal: build/test_j1979_legacy_journal.o build/j1979_legacy_journal.o build/j1979_legacy_scan.o build/j1979_legacy_shape.o build/j1979_legacy_info.o build/j1979_legacy_scan_wire.o build/j1979_legacy_freeze.o build/j1979_legacy_freeze_wire.o build/j1979_legacy_live.o build/j1979_legacy_live_wire.o build/j1979_legacy_live_plan.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_legacy.o build/j1979_elm.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-host-freeze: build/test_j1979_host_freeze.o build/j1979_elm_can_trace.o build/j1979_elm_can_trace_wire.o build/j1979_host_freeze.o build/j1979_elm_can_session.o build/j1979_elm_can_decode.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_elm.o build/cf1.o build/cf1_complete.o build/j1979_isotp.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-can-history: build/test_j1979_can_history.o build/j1979_can_history.o build/j1979_can_history_report.o build/native_json.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-elm-campaign: build/test_j1979_elm_campaign.o build/j1979_elm_can_campaign.o build/j1979_elm_can_campaign_wire.o build/j1979_elm_can_trace.o build/j1979_elm_can_trace_wire.o build/j1979_elm_can_session.o build/j1979_elm_can_decode.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_elm.o build/j1979_can.o build/j1979_live.o $(DECODER_OBJECTS) build/j1979_policy.o build/cf1.o build/cf1_complete.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-elm-journal: build/test_j1979_elm_journal.o build/j1979_elm_can_journal.o build/j1979_elm_can_trace.o build/j1979_elm_can_trace_wire.o build/j1979_elm_can_session.o build/j1979_elm_can_decode.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_elm.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o build/cf1.o build/cf1_complete.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-elm-can: build/test_j1979_elm_can.o build/j1979_elm_can_decode.o build/j1979_elm_can_session.o build/j1979_elm_can_report.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_elm.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-host-maintenance: build/test_j1979_host_maintenance.o build/j1979_elm_can_trace.o build/j1979_elm_can_trace_wire.o build/j1979_host_maintenance_elm.o build/j1979_elm_can_session.o build/j1979_elm_can_decode.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_elm.o build/j1979_host_maintenance_storage.o build/j1979_host_maintenance_socket.o build/j1979_host_maintenance_report.o build/j1979_cm1.o build/j1979_isotp.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-catalogue: build/test_j1979_catalogue.o build/native_json.o build/j1979_catalogue.o build/j1979_catalogue_exact.o build/j1979_catalogue_projection.o build/j1979_catalogue_report.o build/cf1.o build/cf1_complete.o build/cl1.o build/j1979_legacy_live_wire.o build/j1979_legacy_live.o build/j1979_legacy_live_plan.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_legacy.o build/j1979_elm.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ $(LDLIBS) -o $@

test-j1979-can-network: build/test_j1979_can_network.o build/j1979_can_network.o build/j1979_can_network_report.o build/j1979_can_network_scan.o build/j1979_isotp.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-legacy-mutation: build/test_j1979_legacy_mutation.o build/j1979_legacy_mutation_inspection.o build/j1979_legacy_mutation_parse.o build/native_json.o build/j1979_legacy_mutation.o build/j1979_legacy_mutation_report.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_legacy.o build/j1979_elm.o build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-legacy-live: build/test_j1979_legacy_live.o build/j1979_legacy_live_plan.o build/j1979_legacy_live.o build/j1979_legacy_live_report.o build/j1979_legacy_live_wire.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_elm.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-legacy-freeze: build/test_j1979_legacy_freeze.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_legacy_freeze.o build/j1979_legacy_shape.o build/j1979_legacy_freeze_acquire.o build/j1979_legacy_freeze_wire.o build/j1979_legacy_freeze_report.o build/j1979_legacy.o build/j1979_elm.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-legacy-scan: build/test_j1979_legacy_scan.o build/j1979_legacy_discovery_binding.o build/j1979_legacy_discovery_history.o build/native_json.o build/j1979_legacy_scan_wire.o build/j1979_legacy_scan.o build/j1979_legacy_shape.o build/j1979_legacy_info.o build/j1979_legacy_report.o build/j1979_legacy_monitor.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_elm.o build/j1979_policy.o $(DECODER_OBJECTS)
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-legacy: build/test_j1979_legacy.o build/j1979_legacy.o build/j1979_legacy_identity.o build/j1979_legacy_freeze_plan.o build/j1979_elm.o build/j1979_discovery.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-projection: build/test_j1979_projection.o build/j1979_multi.o build/j1979_status.o build/j1979_context.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-cu1: build/test_j1979_cu1.o build/j1979_cu1.o build/j1979_cj1.o build/j1979_policy.o $(DECODER_OBJECTS)
	$(CXX) $(LDFLAGS) $^ $(LDLIBS) -o $@

test-j1979-cj1: build/test_j1979_cj1.o build/j1979_cj1.o build/j1979_policy.o $(DECODER_OBJECTS)
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-cm1: build/test_j1979_cm1.o build/j1979_cm1.o build/j1979_cu45.o build/j1979_cu45_session.o build/j1979_cu45_report.o
	$(CXX) $(LDFLAGS) $^ $(LDLIBS) -o $@

test-j1979-context: build/test_j1979_context.o build/j1979_context.o
	$(CXX) $(LDFLAGS) $^ -o $@

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

test-j1979-live: build/test_j1979_live.o build/j1979_native_live_inspection.o build/native_json.o build/j1979_live.o build/j1979_can.o $(DECODER_OBJECTS) build/j1979_policy.o
	$(CXX) $(LDFLAGS) $^ -o $@

test-j1979-cl1: build/test_j1979_cl1.o build/cl1.o build/cf1.o
	$(CXX) $(LDFLAGS) $^ -o $@

clean:
	rm -rf build
	rm -f $(TARGET) test-j1979-legacy-scan-history test-j1979-native-can-inspection test-j1979-historical-can-network test-j1979-historical-can-plan test-native-json-report test-j1979-network-history test-j1979-legacy-discovery-history test-j1979-can-history test-j1979-elm-campaign test-j1979-elm-journal test-j1979-legacy-decoding test-j1979-legacy-journal test-j1979-host-freeze test-j1979-elm-can test-j1979-host-maintenance test-j1979-catalogue test-j1979-can-network test-j1979-legacy-mutation test-j1979-legacy-live test-j1979-legacy-freeze test-j1979-legacy-scan test-j1979-legacy test-j1979-projection test-cu1 test-j1979-cj1 test-j1979-cm1 test-j1979-context test-j1979-measurement test-j1979-monitor test-j1979-vehicle-info test-j1979-native-c test-cf1 test-cu2 test-cu3 test-j1979-can test-j1979-isotp test-j1979-live test-j1979-cl1

PREFIX ?= /usr/local
DESTDIR ?=

install: $(TARGET)
	install -d "$(DESTDIR)$(PREFIX)/bin"
	install -m 0755 "$(TARGET)" "$(DESTDIR)$(PREFIX)/bin/$(TARGET)"

deb: $(TARGET)
	sh packaging/build-deb.sh "$(TARGET)"
