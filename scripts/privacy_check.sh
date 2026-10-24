#!/usr/bin/env bash
# READ-ONLY: report the privacy-related settings that the Settings UI controls.
set -u
ADB="${ADB:-/c/Users/PC/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TV="${TV:-10.0.0.213:5555}"
export MSYS_NO_PATHCONV=1
get() { "$ADB" -s "$TV" shell settings get "$1" "$2" < /dev/null 2>&1 | tr -d '\r'; }

# key  namespace  value-that-means-private  label
while read -r ns key want label; do
  v="$(get "$ns" "$key")"
  [ "$v" = "$want" ] && mark="OK " || mark="-- "
  printf '%s %-48s = %-6s (private = %s)  %s\n' "$mark" "$key" "$v" "$want" "$label"
done <<'EOF'
secure settingsApp:deviceUsageData:value 0 Device Usage Data
secure settingsApp:appUsageData:value 0 Collect App Usage Data
secure advertisingIdApp:interestBasedAds:value 0 Interest-based Ads
secure limit_ad_tracking 1 Limit ad tracking (Android)
secure amz_limit_ad_tracking 1 Limit ad tracking (Amazon)
secure non_amz_limit_ad_tracking 1 Limit ad tracking (non-Amazon apps)
secure USAGE_METRICS_UPLOAD_ENABLED 0 Usage metrics upload
secure usage_metrics_marketing_enabled 0 Marketing usage metrics
secure pact_usage_collection_enabled 0 PACT usage collection
EOF
echo "(These keys are inferred from names; the Settings UI is the authority. '--' after you toggle in the UI means that key isn't tied to that toggle.)"
