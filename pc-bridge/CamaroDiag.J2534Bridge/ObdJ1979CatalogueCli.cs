using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>Pure file processing: dispatch before any driver or vehicle command.</summary>
internal static class ObdJ1979CatalogueCli
{
    internal const string Command="--j1979-project-catalogue";
    internal sealed record Options(string Catalogue,string Reference,string Pin,string Report,string Vin,string Output);
    internal static Options Parse(string[] args)
    {
        if(args.Length!=13||!args[0].Equals(Command,StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Expected catalogue projection command and six named options.");
        var fields=new Dictionary<string,string>(StringComparer.Ordinal);
        for(int i=1;i<args.Length;i+=2)
        {
            var key=args[i];var value=args[i+1];
            if(key is not ("--catalogue" or "--reference" or "--reference-sha256" or "--report" or "--vin" or "--output")||
                string.IsNullOrWhiteSpace(value)||value.StartsWith("--",StringComparison.Ordinal)||!fields.TryAdd(key,value))
                throw new ArgumentException("Unknown, missing or repeated catalogue option.");
        }
        var result=new Options(fields["--catalogue"],fields["--reference"],fields["--reference-sha256"],
            fields["--report"],fields["--vin"],fields["--output"]);
        ObdJ1979DocumentedCatalogue.Digest(result.Pin);
        var paths=new[]{result.Catalogue,result.Reference,result.Report,result.Output}.Select(Path.GetFullPath).ToArray();
        if(paths.Distinct(StringComparer.OrdinalIgnoreCase).Count()!=paths.Length)
            throw new ArgumentException("Use distinct input and new output paths.");
        return result;
    }
    internal static int Run(string[] args)
    {
        try
        {
            var options=Parse(args);
            var result=ObdJ1979CatalogueProjection.ProjectFiles(options.Catalogue,options.Reference,options.Pin,
                options.Report,options.Vin);
            ObdJ1979CatalogueProjection.WriteNew(options.Output,result);
            Console.WriteLine(JsonSerializer.Serialize(new {
                output=options.Output,source_application_replay_verified=true,source_replay_verified=result["source_replay_verified"],
                native_capture_replayed=result.GetValueOrDefault("native_capture_replayed",false),
                normative_compliance_verified=false,physical_vehicle_verified=false
            }));
            return 0;
        }
        catch(Exception e) when(e is ArgumentException or IOException or InvalidDataException or UnauthorizedAccessException or JsonException or
            FormatException or KeyNotFoundException or InvalidOperationException or OverflowException)
        {
            Console.Error.WriteLine("J1979 catalogue: "+e.Message);return 6;
        }
    }
}
