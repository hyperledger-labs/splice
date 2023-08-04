{ stdenv, fetchFromGitHub, jq, moreutils }:

stdenv.mkDerivation {
  name = "da-grafana-dashboards";
  src = fetchFromGitHub {
    owner = "digital-asset";
    repo = "daml-platform-observability-example";
    rev = "f40b19eceaa461e4a15a4fbe4668fc2033d943ad";
    hash = "sha256-QEt8r4LspLuRbESJiWAynqSb9d9xkeeMJygn3ggLJMo=";
  };
  nativeBuildInputs = [
    jq
    moreutils
  ];
  installPhase = ''
    # Remove all files except the specified folders
    find . -type f -not \( -path "*grafana/dashboards/Platform/*" -o -path "*grafana/dashboards/Participant*" \) -delete
    # Remove unused dashboard
    rm  grafana/dashboards/Platform/logs.json
    # Copy the specific folders to the output
    mkdir -p $out
    cp -R grafana/dashboards/Platform $out/platform
    cp -R grafana/dashboards/Participant $out/participant
    # Add the adhoc filter to all the dashboards
    # It has a default values set that filters out all the data to prevent running really expensive queries when we first load the dashboard
    for file in $out/*/*.json; do
        jq '.templating.list += [{
                    "datasource": {
                      "type": "prometheus",
                      "uid": "''${datasource}"
                    },
                    "filters": [
                      {
                        "condition": "",
                        "key": "build_num",
                        "operator": "=",
                        "value": "non_existing"
                      }
                    ],
                    "hide": 0,
                    "name": "filter",
                    "skipUrlSync": false,
                    "type": "adhoc"
                  }]' "$file" | sponge "$file"
    done
    '';
}
