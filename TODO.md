# TODO - Fix estimated time + charging per hour + duplicate mA

- [x] Step 1: Update `BatteryRepository.processTelemetry()` so `timeToFullMinutes` is computed whenever possible (not only when capacity estimate is available).

- [x] Step 2: Update UI (`DashboardScreen.HeroCard`) so “Estimated charging time” is driven by `timeToFullMinutes`.

- [x] Step 3: Fix duplicate mA display: keep mA only in `BatteryGauge` (hide HeroCard’s mA/rate row when gauge is already showing mA).

- [x] Step 4: Build/run app and verify:

  - estimated charging time shows
  - charging per hour tile shows (or a stable placeholder shows)
  - mA shows only once

