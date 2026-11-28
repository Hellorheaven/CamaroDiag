import copy
import unittest
from camarodiagconnectors.j1979_can_network import CanFrame, discover, scan_network
from camarodiagconnectors.j1979_network_inspection import inspect_network

VIN="1G1FF3D36C9104199"


class Link:
    def __init__(self):
        self.frames=iter([CanFrame(0x7E8,False,bytes.fromhex("06410008000000CC")),
                          CanFrame(0x7E9,False,bytes.fromhex("06410008000000CC"))])
    def send_discovery(self,timeout):
        pass
    def receive(self,timeout):
        try:
            return next(self.frames)
        except StopIteration:
            raise TimeoutError()


class Channel:
    def __enter__(self):
        return self
    def __exit__(self,*args):
        pass
    def exchange(self,req,timeout):
        return {bytes.fromhex(key):value for key,value in {
            "0902":bytes.fromhex("490201")+VIN.encode(),
            "03":bytes.fromhex("4300"),"07":bytes.fromhex("4700"),"0A":bytes.fromhex("4A00"),
            "0100":bytes.fromhex("410008000000"),"0105":bytes.fromhex("410564"),
            "020200":bytes.fromhex("4202000000"),"0600":bytes.fromhex("460000000000"),
            "0800":bytes.fromhex("480080000000"),"0900":bytes.fromhex("490040000000")}.items()}[req]


def report():
    return {"schema":"camarodiag-functional-j1979-can-network-v1","addressing":"11BIT",
            **scan_network(discover(Link(),False),[0x7E8,0x7E9],lambda tx,rx:Channel(),expected_vin=VIN)}


class NetworkInspectionTests(unittest.TestCase):
    def test_independent_per_ecu_replay(self):
        r=inspect_network(report(),VIN)
        self.assertTrue(r["completed"])
        self.assertFalse(r["physical_vehicle_verified"])
        self.assertEqual([x["response_id"] for x in r["ecu_reports"]],[0x7E8,0x7E9])
        self.assertEqual(r["ecu_reports"][1]["measurements"][0]["number"],60)

    def test_discovery_only(self):
        d={"schema":"camarodiag-functional-j1979-can-discovery-v1",**discover(Link(),False)}
        result=inspect_network(d)
        self.assertEqual(result["discovery"]["eligible_response_ids"],[0x7E8,0x7E9])
        self.assertEqual(result["ecu_reports"],[])

    def test_tampered_reports(self):
        for modify in (lambda r:r["discovery"]["responders"][0].update(supported_pids=[6]),
                       lambda r:r["ecu_reports"][0].update(rx_can_id="7E9"),
                       lambda r:r["ecu_reports"][0]["report"]["steps"].pop(),
                       lambda r:r["ecu_reports"][0]["report"]["steps"][0].update(request_hex="04"),
                       lambda r:r["ecu_reports"][0]["report"]["measurements"][0].update(number=0),
                       lambda r:r["ecu_reports"][0]["report"].update(observed_vin="1G1FF3D36C9104198"),
                       lambda r:r.update(selected_response_ids=["7E9","7E8"])):
            r=report()
            modify(r)
            with self.assertRaises(ValueError):
                inspect_network(r)

    def test_android_short_dlc(self):
        frame={"responseId":0x7E8,"dataHex":"06410008000000"}
        d={"schema":"camarodiag-j1979-network-v1","protocol":6,"declaredVin":VIN,
           "completed":True,"selectedResponseIds":[],"ecus":[],
           "discovery":{"completed":True,"requestSent":True,"stopReason":None,
                        "observedProtocol":6,"rawProtocol":"6","frames":[frame],
                        "responders":[{"requestId":0x7E0,"responseId":0x7E8,"status":"PASSED","detail":None,
                                       "supportedPids":[5],"nextPage":False,"negativeResponseCode":None,
                                       "frames":[frame["dataHex"]]}]}}
        self.assertTrue(inspect_network(d)["verified"])

    def test_unverified_selection_remains_negative_evidence(self):
        result=scan_network(discover(Link(),False),[0x7EA],lambda tx,rx: self.fail("unexpected channel"),expected_vin=VIN)
        result.update(schema="camarodiag-functional-j1979-can-network-v1",addressing="11BIT")
        inspected=inspect_network(result)
        self.assertTrue(inspected["verified"])
        self.assertFalse(inspected["completed"])
        self.assertEqual(inspected["ecu_reports"][0]["steps"],[])
