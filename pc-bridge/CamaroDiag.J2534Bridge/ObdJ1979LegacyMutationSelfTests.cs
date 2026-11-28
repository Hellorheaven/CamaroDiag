namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979LegacyMutationSelfTests
{
    internal static int Run()
    {
        int checks=0;
        void Check(bool value,string name)
        {
            checks++;
            if(!value) throw new InvalidOperationException("Legacy mutation: "+name);
        }
        const string vin="1G1FA1RXXR0123456";
        byte[] vinData=[0,0,0,..System.Text.Encoding.ASCII.GetBytes(vin)];
        var fragments=vinData.Chunk(4).Select((data,i)=>new byte[]{0x49,2,(byte)(i+1),..data}).ToArray();
        int sends=0;
        bool saved=false,reset=false,failSave=false,moving=false,extra=false,missingAck=false,failAfter=false,wrongVin=false;
        bool secondEvapSource=false, missingPending=false, residualPending=false;
        var seen=new List<string>();
        ObdLegacyJ2534Transport.Exchange Response(string request,params (byte Source,byte[] Data)[] rows) =>
            new(request,rows.Select((x,i)=>new ObdLegacyJ2534Transport.Packet(x.Source,
                Convert.ToHexString(new byte[]{0x48,0x6B,x.Source,..x.Data}),x.Data,(uint)i)).ToArray());
        ObdLegacyJ2534Transport.Exchange Read(byte[] request,int timeout)
        {
            var hex=Convert.ToHexString(request);seen.Add(hex);
            if(failAfter&&reset) throw new IOException("Lost transport after ACK");
            return hex switch
            {
                "0100" => extra ? Response(hex,(0x10,[0x41,0,0x80,0,0,0]),(0x11,[0x41,0,0x80,0,0,0]),
                    (0x12,[0x41,0,0x80,0,0,0])) :
                    Response(hex,(0x10,[0x41,0,0x80,0,0,0]),(0x11,[0x41,0,0x80,0,0,0])),
                "0901" => Response(hex,(0x10,[0x49,1,5])),
                "0902" => Response(hex,fragments.Select((r,i)=>(Source:(byte)0x10,
                    Data:wrongVin&&i==4 ? new byte[]{0x49,2,5,0x33,0x34,0x35,0x37}:r)).ToArray()),
                "03" => reset ? Response(hex,(0x10,[0x43,0,0,0,0,0,0]),(0x11,[0x43,0,0,0,0,0,0])) :
                    Response(hex,(0x10,[0x43,1,0x23,0,0,0,0]),(0x11,[0x43,0,0,0,0,0,0])),
                "07" => missingPending && reset ? Response(hex,(0x10,[0x47,0,0,0,0,0,0])) :
                    Response(hex,(0x10,[0x47,0,0,0,0,0,0]),
                        (0x11,[0x47,residualPending && reset ? (byte)1 : (byte)0,
                            residualPending && reset ? (byte)0x23 : (byte)0,0,0,0,0])),
                "0A" => Response(hex,(0x10,[0x4A,1,0x23,0,0,0,0])),
                "0101" => Response(hex,(0x10,[0x41,1,0,0,0,0])),
                "010D" => Response(hex,(0x10,[0x41,0x0D,moving?(byte)1:(byte)0])),
                "010C" => Response(hex,(0x10,[0x41,0x0C,0,0])),
                "08000000000000" => secondEvapSource ? Response(hex,(0x10,[0x48,0,0,0x80,0,0,0]),
                    (0x11,[0x48,0,0,0x80,0,0,0])) : Response(hex,(0x10,[0x48,0,0,0x80,0,0,0])),
                _=>throw new InvalidOperationException("Unexpected request "+hex)
            };
        }
        ObdLegacyJ2534Transport.Exchange Send(ObdJ1979LegacyMutation.Operation operation)
        {
            Check(saved,"baseline persisted before transmission");
            sends++; reset=true;
            return operation==ObdJ1979LegacyMutation.Operation.Clear ?
                missingAck ? Response("04",(0x10,[0x44])) : Response("04",(0x10,[0x44]),(0x11,[0x44])) :
                Response("08010000000000",(0x10,[0x48,1,0,0,0,0,0]));
        }
        ObdJ1979LegacyMutation.Result Run(ObdJ1979LegacyMutation.Operation operation=ObdJ1979LegacyMutation.Operation.Clear)
        {
            sends=0;saved=false;reset=false;seen.Clear();
            return ObdJ1979LegacyMutation.Run(operation,vin,0x10,new byte[]{0x10,0x11},true,true,
                ()=>{},Read,Send,baseline=>
                {
                    Check(!baseline.Attempted && baseline.Outcome=="BASELINE_SAVED","before write snapshot");
                    if(failSave)throw new IOException("Disk unavailable");
                    saved=true;
                });
        }
        var good=Run();
        Check(good.Outcome=="ACKNOWLEDGED_READBACK" && sends==1 && good.ObservedVin==vin,"one request after confirmed scope");
        Check(good.BeforeConfirmed["10"]!.SequenceEqual(new[]{"P0123"}) &&
            good.AfterConfirmed["10"]!.Length==0,"before and after source evidence");
        Check(good.Observations.Any(x=>x.Stage=="AFTER" && x.RequestHex=="0A"),"permanent raw evidence retained");
        Check(!good.WholeVehicleCoverageVerified,"no exhaustive scope claim");
        Check(ObdJ1979LegacyMutationCli.ExitCode(ObdJ1979LegacyMutation.Operation.Clear,good)==0,
            "complete clean readback exit success");
        missingPending=true;
        var partial=Run();
        Check(partial.Outcome=="ACKNOWLEDGED_PARTIAL" && partial.Reason=="PENDING_READBACK_UNAVAILABLE" &&
            partial.AfterDtcs["11"]["07"] is null && sends==1 &&
            ObdJ1979LegacyMutationCli.ExitCode(ObdJ1979LegacyMutation.Operation.Clear,partial)==7,
            "missing pending readback is partial, not successful");
        missingPending=false;
        residualPending=true;
        var residual=Run();
        Check(residual.Reason=="DTCS_STILL_PRESENT" && sends==1 &&
            ObdJ1979LegacyMutationCli.ExitCode(ObdJ1979LegacyMutation.Operation.Clear,residual)==7,
            "pending DTC still present is non-success");
        residualPending=false;
        Check(ObdJ1979LegacyMutationCli.ExitCode(ObdJ1979LegacyMutation.Operation.Clear,null)==7,
            "missing result is non-success");
        moving=true;Check(Run().Outcome=="NOT_TESTED"&&sends==0,"moving blocks");moving=false;
        extra=true;Check(Run().Outcome=="NOT_TESTED"&&sends==0,"unexpected responder blocks");extra=false;
        wrongVin=true;Check(Run().Outcome=="NOT_TESTED"&&sends==0,"wrong VIN blocks");wrongVin=false;
        failSave=true;Check(Run().Outcome=="NOT_TESTED"&&sends==0,"disk failure blocks");failSave=false;
        missingAck=true;Check(Run().Outcome=="UNCERTAIN"&&sends==1,"missing ACK no retry");missingAck=false;
        failAfter=true;Check(Run().Outcome=="ACKNOWLEDGED_PARTIAL"&&sends==1,"readback failure preserves ACK");failAfter=false;
        secondEvapSource=true;Check(Run(ObdJ1979LegacyMutation.Operation.EvapPreparation).Outcome=="NOT_TESTED"&&sends==0,
            "additional advertised TID blocks");secondEvapSource=false;
        var evap=Run(ObdJ1979LegacyMutation.Operation.EvapPreparation);
        Check(evap.Outcome=="ACKNOWLEDGED"&&evap.Reason=="LEAK_TEST_NOT_PERFORMED"&&sends==1,"preparation is not a leak test");
        Check(ObdJ1979LegacyMutationCli.ExitCode(ObdJ1979LegacyMutation.Operation.EvapPreparation,evap)==0 &&
            ObdJ1979LegacyMutationCli.ExitCode(ObdJ1979LegacyMutation.Operation.Clear,evap)==7,
            "EVAP ACK only succeeds for preparation, never for clear");
        foreach(var approval in new[]{(true,false),(false,true)})
        {
            try
            {
                ObdJ1979LegacyMutation.Run(ObdJ1979LegacyMutation.Operation.Clear,vin,0x10,new byte[]{0x10,0x11},
                    approval.Item1,approval.Item2,()=>{},(_,_)=>throw new InvalidOperationException("read before consent"),
                    _=>throw new InvalidOperationException("send before consent"),_=>{});
                throw new InvalidOperationException("Consent required");
            }
            catch(UnauthorizedAccessException){checks++;}
        }
        return checks;
    }
}
