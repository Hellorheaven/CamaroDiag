#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef BuildDir
  #define BuildDir "publish"
#endif
#ifndef Channel
  #define Channel "release"
#endif
#ifndef Arch
  #define Arch "x64"
#endif
#ifndef OutputName
  #define OutputName "CamaroDiag-J2534-Bridge-Setup"
#endif

[Setup]
AppId=CamaroDiag.J2534Bridge.{#Channel}.{#Arch}
AppName=CamaroDiag J2534 Bridge ({#Channel}, {#Arch})
AppVersion={#AppVersion}
AppPublisher=CamaroDiag
AppPublisherURL=https://github.com/Hellorheaven/CamaroDiag
AppSupportURL=https://github.com/Hellorheaven/CamaroDiag
DefaultDirName={localappdata}\Programs\CamaroDiag\J2534Bridge\{#Channel}\{#Arch}
DefaultGroupName=CamaroDiag J2534 Bridge {#Channel} {#Arch}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
Uninstallable=yes
UninstallDisplayIcon={app}\CamaroDiag.J2534Bridge.exe
OutputDir=..\\..\\dist
OutputBaseFilename={#OutputName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile=assets\\CamaroDiag.ico
#if Arch == "x64"
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#else
ArchitecturesAllowed=x86compatible
#endif
CloseApplications=yes
RestartApplications=no
SetupLogging=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked

[Files]
Source: "{#BuildDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\CamaroDiag J2534 Bridge {#Channel} {#Arch}"; Filename: "{app}\CamaroDiag.J2534Bridge.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\CamaroDiag J2534 Bridge {#Channel} {#Arch}"; Filename: "{app}\CamaroDiag.J2534Bridge.exe"; WorkingDir: "{app}"; Tasks: desktopicon
