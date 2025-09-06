REPO_DIR="$(realpath device/xiaomi/sm6125-common)"

if [ -f "hardware/lineage/interfaces/health/aidl/default/FastCharge.cpp" ]; then
  echo "Reverting IFastCharge HAL changes..."

  pushd "hardware/lineage/interfaces" > /dev/null
  git fetch https://github.com/exynos1280/android_hardware_lineage_interfaces edf551e9afacee35e43e021b96577067fd5d02f0

  git cherry-pick edf551e9afacee35e43e021b96577067fd5d02f0

  popd > /dev/null
fi

if ! grep -q "disabled" "bootable/deprecated-ota/applypatch/vendor_flash_recovery.rc"; then
  echo "Disabling vendor_flash_recovery service..."
  
  pushd "bootable/deprecated-ota/applypatch" > /dev/null
  
  sed -i '/^service vendor_flash_recovery \/vendor\/bin\/install-recovery\.sh$/a\    disabled' vendor_flash_recovery.rc
  
  popd > /dev/null
fi

if ! grep -q "config.show4gForLte = true" "frameworks/base/packages/SettingsLib/src/com/android/settingslib/mobile/MobileMappings.java"; then
  echo "Applying 4G icon force patch..."
  
  pushd "frameworks/base" > /dev/null
  
  git apply "$REPO_DIR/patches/0001-Force-4G-icon-on-status-bar-for-all-carriers.patch"
  
  popd > /dev/null
  echo "4G icon patch applied successfully."
fi
