if [ -f "hardware/lineage/interfaces/health/aidl/default/FastCharge.cpp" ]; then
  echo "Reverting IFastCharge HAL changes..."

  pushd "hardware/lineage/interfaces" > /dev/null
  git fetch https://github.com/exynos1280/android_hardware_lineage_interfaces edf551e9afacee35e43e021b96577067fd5d02f0

  git cherry-pick edf551e9afacee35e43e021b96577067fd5d02f0

  popd > /dev/null
fi
