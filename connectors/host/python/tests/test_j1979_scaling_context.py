import unittest

from camarodiagconnectors.j1979_scaling_context import (
    Context, config_data, context_from_steps, dependency, project,
)


def row(request, response=None, status="PASSED", **kw):
    return dict(request_hex=request, response_hex=response, status=status, **kw)


INVENTORY = [
    row("0100", "410000210001"),
    row("0120", "412010001001"),
    row("0140", "414010030000"),
]
CONFIG = [
    row("014F", "414F0410004D", "RAW"),
    row("0150", "415064000000", "RAW"),
]


class ScalingContextTest(unittest.TestCase):
    def test_custom_ranges(self):
        ctx = context_from_steps(INVENTORY + CONFIG)
        self.assertEqual(ctx.get(0x4F).state, "ECU_CONFIGURATION")
        self.assertEqual(ctx.get(0x50).state, "ECU_CONFIGURATION")
        for req, res, index, expected in [
            ("010B", "410BFF", 0, 770),
            ("0110", "4110FFFF", 0, 1000),
            ("0144", "4144FFFF", 0, 4),
            ("0124", "4124FFFFFFFF", 0, 4),
            ("0124", "4124FFFFFFFF", 1, 16),
            ("0134", "4134FFFF8000", 1, 0),
            ("020B00", "420B00FF", 0, 770),
        ]:
            with self.subTest(req=req, index=index):
                result = ctx.decode(bytes.fromhex(req), bytes.fromhex(res))
                self.assertAlmostEqual(result["signals"][index]["number"], expected)

    def test_custom_current_is_twos_complement(self):
        ctx = context_from_steps(INVENTORY + [row("014F", "414F0400404D", "RAW")])
        for encoded, expected in [("0000", 0), ("7FFF", 63.998046875),
                                  ("8000", -64), ("9C40", -49.875), ("FFFF", -0.001953125)]:
            with self.subTest(encoded=encoded):
                result = ctx.decode(bytes.fromhex("0134"), bytes.fromhex("41347D00" + encoded))
                self.assertAlmostEqual(result["signals"][1]["number"], expected)
                self.assertEqual(result["signals"][1]["scale_source"], "ECU_CONFIGURATION")

    def test_unverified_is_not_default(self):
        for steps in ([], INVENTORY[:1], INVENTORY, INVENTORY + [
                row("0150", "415064010000", "RAW")]):
            ctx = context_from_steps(steps)
            self.assertEqual(ctx.get(0x50).state, "UNVERIFIED")
            record = ctx.decode(bytes.fromhex("0110"), bytes.fromhex("41101234"))
            self.assertIsNone(record["signals"][0]["number"])

    def test_terminating_page_proves_absence(self):
        ctx = context_from_steps([row("0100", "410000210000")])
        self.assertEqual(ctx.get(0x50).state, "DEFAULT_UNSUPPORTED")
        value = ctx.decode(bytes.fromhex("0110"), bytes.fromhex("41101234"))
        self.assertAlmostEqual(value["signals"][0]["number"], 46.6)

    def test_duplicate_config_and_inventory(self):
        ctx = context_from_steps(INVENTORY + CONFIG + CONFIG[:1])
        self.assertEqual(ctx.get(0x4F).state, "UNVERIFIED")
        ctx = context_from_steps(INVENTORY + INVENTORY[:1] + CONFIG)
        self.assertEqual(ctx.get(0x4F).state, "UNVERIFIED")

    def test_wrong_frame_service_and_length(self):
        ctx = context_from_steps(INVENTORY + CONFIG)
        for req, res in [
            ("020B00", "420B01FF"),
            ("010B", "410CFF"),
            ("010B", "410BFFFF"),
            ("020B01", "420B01FF"),
        ]:
            self.assertIsNone(ctx.decode(bytes.fromhex(req), bytes.fromhex(res)))

    def test_legacy_source_isolation(self):
        steps = []
        for item in INVENTORY + CONFIG + [row("010B", "410BFF")]:
            steps.append(dict(item, selected_response_source="10",
                              records_hex=[item["response_hex"]]))
        steps[3]["selected_response_source"] = "11"
        result = project(steps, legacy=True, legacy_source="10")
        self.assertEqual(result["configuration"][0]["state"], "UNVERIFIED")
        self.assertIsNone(result["records"][0]["signals"][0]["number"])
        self.assertEqual(project(steps, legacy=True)["records"], [])

    def test_configuration_validation(self):
        self.assertIsNone(config_data(0x50, bytes.fromhex("64010000")))
        self.assertIsNone(config_data(0x4F, b"\x00"))
        self.assertEqual(config_data(0x50, bytes(4)), bytes(4))
        self.assertIsNone(dependency(0x05))

    def test_projection_preserves_raw_and_excludes_failed_samples(self):
        result = project(INVENTORY + CONFIG + [
            row("010B", "410BFF"), row("0110", "4110FFFF", "FAILED")])
        self.assertEqual(len(result["records"]), 1)
        self.assertEqual(result["records"][0]["signals"][0]["raw_encoded"], 255)


if __name__ == "__main__":
    unittest.main()
