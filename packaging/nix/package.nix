{ lib, stdenv, appimageTools, fetchurl, release }:
let
  pname = "meld";
  version = release.version;
  asset = release.linux.${stdenv.hostPlatform.system};
  src = fetchurl { inherit (asset) url hash; };
  contents = appimageTools.extract { inherit pname version src; };
in
appimageTools.wrapType2 {
  inherit pname version src;
  extraPkgs = pkgs: with pkgs; [
    gtk3 webkitgtk_4_1 libsecret dbus alsa-plugins
  ];
  extraInstallCommands = ''
    install -Dm644 ${contents}/Meld.desktop $out/share/applications/meld.desktop
    substituteInPlace $out/share/applications/meld.desktop \
      --replace-fail 'Exec=bin/Meld' 'Exec=meld'
    install -Dm644 ${contents}/Meld.png $out/share/icons/hicolor/512x512/apps/Meld.png
  '';
  meta = {
    description = "YouTube Music desktop client";
    homepage = "https://github.com/FrancescoGrazioso/Meld";
    license = lib.licenses.gpl3;
    platforms = [ "x86_64-linux" "aarch64-linux" ];
    mainProgram = pname;
  };
}
