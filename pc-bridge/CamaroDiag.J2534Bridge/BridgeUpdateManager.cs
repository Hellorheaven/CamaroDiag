using System.Diagnostics;
using System.Net.Http.Headers;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.Json;
namespace CamaroDiag.J2534Bridge;
internal static class BridgeUpdateManager {
 private const string Api="https://api.github.com/repos/Hellorheaven/CamaroDiag/releases/latest";
 private static readonly HttpClient Http=MakeClient();
 private static HttpClient MakeClient(){var h=new HttpClient{Timeout=TimeSpan.FromSeconds(30)};h.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("CamaroDiag-J2534-Bridge","1.0"));h.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));return h;}
 public static async Task<bool> CheckAndOfferUpdateAsync(){
  try{
#if DEBUG
   return false; // Debug builds are not distributed through GitHub Releases.
#else
   const string channel="release";
#endif
   var arch=RuntimeInformation.ProcessArchitecture switch{Architecture.X64=>"win-x64",Architecture.X86=>"win-x86",_=>null};if(arch is null)return false;
   using var res=await Http.GetAsync(Api);res.EnsureSuccessStatusCode();await using var st=await res.Content.ReadAsStreamAsync();using var doc=await JsonDocument.ParseAsync(st);var root=doc.RootElement;
   if((root.TryGetProperty("draft",out var d)&&d.GetBoolean())||(root.TryGetProperty("prerelease",out var p)&&p.GetBoolean()))return false;
   if(!Version.TryParse(root.GetProperty("tag_name").GetString()?.TrimStart('v','V'),out var latest))return false;
   var v=Assembly.GetExecutingAssembly().GetName().Version??new Version(0,0,0);var current=new Version(v.Major,v.Minor,Math.Max(0,v.Build));var target=new Version(latest.Major,latest.Minor,Math.Max(0,latest.Build));if(target<=current)return false;
   var expected=$"CamaroDiag-J2534-Bridge-Setup-{channel}-{arch}.exe";string? url=null;foreach(var a in root.GetProperty("assets").EnumerateArray())if(a.GetProperty("name").GetString()==expected)url=a.GetProperty("browser_download_url").GetString();
   if(string.IsNullOrWhiteSpace(url)||!url.StartsWith("https://github.com/",StringComparison.OrdinalIgnoreCase))return false;
   Console.WriteLine($"Bridge update: {current} -> {target} ({channel}, {arch})");Console.Write("Download and install? [y/N]: ");var answer=Console.ReadLine();if(answer?.Trim().ToLowerInvariant() is not ("y" or "yes"))return false;
   var installer=Path.Combine(Path.GetTempPath(),$"CamaroDiag-Bridge-Setup-{channel}-{arch}-{target}.exe");await File.WriteAllBytesAsync(installer,await Http.GetByteArrayAsync(url));
   var script=Path.Combine(Path.GetTempPath(),$"CamaroDiag-Bridge-{Guid.NewGuid():N}.ps1");var dest=AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
   var ps=$@"$ErrorActionPreference='Stop'
try {{
 Wait-Process -Id {Environment.ProcessId} -ErrorAction SilentlyContinue
 Start-Process -FilePath '{Esc(installer)}' -ArgumentList @('/SILENT','/NORESTART','/DIR=""{Esc(dest)}""') -Verb RunAs -Wait
 Remove-Item '{Esc(installer)}' -Force -ErrorAction SilentlyContinue
 Write-Host 'CamaroDiag Bridge updated.' -ForegroundColor Green
}} catch {{ Write-Host ('Update failed: '+$_.Exception.Message) -ForegroundColor Red }}
Read-Host 'Press Enter'";
   await File.WriteAllTextAsync(script,ps);var start=new ProcessStartInfo("powershell.exe"){UseShellExecute=true,WorkingDirectory=dest};foreach(var a in new[]{"-NoProfile","-ExecutionPolicy","Bypass","-File",script})start.ArgumentList.Add(a);Process.Start(start);return true;
  }catch(Exception e){Console.WriteLine($"Update check unavailable: {e.Message}");return false;}
 }
 private static string Esc(string s)=>s.Replace("'","''");
}