{ stdenv, fetchFromGitHub, jq, moreutils }:

stdenv.mkDerivation {
  name = "da-grafana-dashboards";
  src = fetchFromGitHub {
    owner = "digital-asset";
    repo = "daml-platform-observability-example";
    rev = "6a7e9ad1bec4c652df63a0d45f59d57fc898c63c";
    hash = "sha256-r6llNbksvBY5ZvXBP/4oIlNqqEgysWJujl/JINOnWXA=";
  };
  nativeBuildInputs = [
    jq
    moreutils
  ];
  installPhase = ''
    # Remove all files except the specified folders
    find . -type f -not \( -path "*grafana/dashboards/Platform/*" -o -path "*grafana/dashboards/Participant*" -o -path "*grafana/dashboards/Canton*" \) -delete
    # Remove unused dashboard
    rm  grafana/dashboards/Platform/logs.json
    # Copy the specific folders to the output
    mkdir -p $out
    cp -R grafana/dashboards/Platform $out/platform
    cp -R grafana/dashboards/Platform $out/filtered-platform
    cp -R grafana/dashboards/Participant $out/participant
    cp -R grafana/dashboards/Participant $out/filtered-participant
    cp -R grafana/dashboards/Canton $out/canton
    cp -R grafana/dashboards/Canton $out/filtered-canton
    # Replace participant1 with participant
    find $out -type f -path '*/canton/*' -exec sed -i 's/participant1/participant/g' {} \;
    # Replace topology with topology_x
    find $out -type f -path '*/canton/*' -exec sed -i 's/topology_store/topology_store_x/g' {} \;
    # Add the adhoc filter to all the dashboards
    # It has a default values set that filters out all the data to prevent running really expensive queries when we first load the dashboard
    for file in $out/filtered-*/*.json; do
        filtered_file=$(echo $file | sed s/.json/-filtered.json/)
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
